/*
 * Copyright 2017 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.hub.core.storage.jdbc;

import java.io.IOException;
import java.io.StringReader;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.inject.Inject;
import javax.sql.DataSource;

import org.apache.commons.io.IOUtils;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.CharacterStreamArgument;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.result.ResultIterable;
import org.jdbi.v3.core.statement.StatementContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.apicurio.hub.core.beans.ApiContentType;
import io.apicurio.hub.core.beans.ApiDesign;
import io.apicurio.hub.core.beans.ApiDesignChange;
import io.apicurio.hub.core.beans.ApiDesignCollaborator;
import io.apicurio.hub.core.beans.ApiDesignCommand;
import io.apicurio.hub.core.beans.ApiDesignContent;
import io.apicurio.hub.core.beans.ApiPublication;
import io.apicurio.hub.core.beans.Contributor;
import io.apicurio.hub.core.beans.Invitation;
import io.apicurio.hub.core.beans.LinkedAccount;
import io.apicurio.hub.core.beans.LinkedAccountType;
import io.apicurio.hub.core.config.HubConfiguration;
import io.apicurio.hub.core.exceptions.AlreadyExistsException;
import io.apicurio.hub.core.exceptions.NotFoundException;
import io.apicurio.hub.core.storage.IStorage;
import io.apicurio.hub.core.storage.StorageException;

/**
 * A JDBC/SQL implementation of the storage layer.
 * @author eric.wittmann@gmail.com
 */
@ApplicationScoped
@Default
public class JdbcStorage implements IStorage {
    
    private static Logger logger = LoggerFactory.getLogger(JdbcStorage.class);
    private static int DB_VERSION = 4;
    private static Object dbMutex = new Object();

    @Inject
    private HubConfiguration config;
    @Resource(mappedName="java:jboss/datasources/ApicurioDS")
    private DataSource dataSource;
    
    private ISqlStatements sqlStatements;
    private Jdbi jdbi;
    
    @PostConstruct
    public void postConstruct() {
        logger.debug("JDBC Storage constructed successfully.");

        jdbi = Jdbi.create(dataSource);

        switch (config.getJdbcType()) {
            case "h2":
                sqlStatements = new H2SqlStatements();
                break;
            case "mysql5":
                sqlStatements = new MySQL5SqlStatements();
                break;
            case "postgresql9":
                sqlStatements = new PostgreSQL9SqlStatements();
                break;
            default:
                throw new RuntimeException("Unsupported JDBC type: " + config.getJdbcType());
        }
        
        if (config.isJdbcInit()) {
            synchronized (dbMutex) {
                if (!isDatabaseInitialized()) {
                    logger.debug("Database not initialized.");
                    initializeDatabase();
                } else {
                    logger.debug("Database was already initialized, skipping.");
                }
                
                if (!isDatabaseCurrent()) {
                    logger.debug("Old database version detected, upgrading.");
                    upgradeDatabase();
                }
            }
        } else {
            if (!isDatabaseInitialized()) {
                logger.error("Database not initialized.  Please use the DDL scripts to initialize the database before starting the application.");
                throw new RuntimeException("Database not initialized.");
            }
            
            if (!isDatabaseCurrent()) {
                logger.error("Detected an old version of the database.  Please use the DDL upgrade scripts to bring your database up to date.");
                throw new RuntimeException("Database not upgraded.");
            }
        }
    }

    /**
     * @return true if the database has already been initialized
     */
    private boolean isDatabaseInitialized() {
        logger.debug("Checking to see if the DB is initialized.");
        return this.jdbi.withHandle(handle -> {
            ResultIterable<Integer> result = handle.createQuery(this.sqlStatements.isDatabaseInitialized()).mapTo(Integer.class);
            return result.findOnly().intValue() > 0;
        });
    }

    /**
     * @return true if the database has already been initialized
     */
    private boolean isDatabaseCurrent() {
        logger.debug("Checking to see if the DB is up-to-date.");
        int version = this.getDatabaseVersion();
        return version == DB_VERSION;
    }

    /**
     * Initializes the database by executing a number of DDL statements.
     */
    private void initializeDatabase() {
        logger.info("Initializing the Apicurio Hub API database.");
        logger.info("\tDatabase type: " + config.getJdbcType());
        
        final List<String> statements = this.sqlStatements.databaseInitialization();
        logger.debug("---");
        this.jdbi.withHandle( handle -> {
            statements.forEach( statement -> {
                logger.debug(statement);
                handle.createUpdate(statement).execute();
            });
            return null;
        });
        logger.debug("---");
    }

    /**
     * Upgrades the database by executing a number of DDL statements found in DB-specific
     * DDL upgrade scripts.
     */
    private void upgradeDatabase() {
        logger.info("Upgrading the Apicurio Hub API database.");
        
        int fromVersion = this.getDatabaseVersion();
        int toVersion = DB_VERSION;

        logger.info("\tDatabase type: {}", config.getJdbcType());
        logger.info("\tFrom Version:  {}", fromVersion);
        logger.info("\tTo Version:    {}", toVersion);

        final List<String> statements = this.sqlStatements.databaseUpgrade(fromVersion, toVersion);
        logger.debug("---");
        this.jdbi.withHandle( handle -> {
            statements.forEach( statement -> {
                logger.debug(statement);
                handle.createUpdate(statement).execute();
            });
            return null;
        });
        logger.debug("---");
    }
    
    /**
     * Reuturns the current DB version by selecting the value in the 'apicurio' table.
     */
    private int getDatabaseVersion() {
        return this.jdbi.withHandle(handle -> {
            ResultIterable<String> result = handle.createQuery(this.sqlStatements.getDatabaseVersion())
                    .bind(0, "db_version")
                    .mapTo(String.class);
            try {
                String versionStr = result.findOnly();
                int version = Integer.parseInt(versionStr);
                return version;
            } catch (Exception e) {
                return 0;
            }
        });
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#hasOwnerPermission(java.lang.String, java.lang.String)
     */
    @Override
    public boolean hasOwnerPermission(String userId, String designId) throws StorageException {
        try {
            return this.jdbi.withHandle( handle -> {
                // Check for permissions first
                String statement = sqlStatements.hasOwnerPermission();
                Long did = Long.valueOf(designId);
                int count = handle.createQuery(statement)
                    .bind(0, did)
                    .bind(1, userId)
                    .mapTo(Integer.class).findOnly();
                return count == 1;
            });
        } catch (Exception e) {
            throw new StorageException("Error checking permission.", e);
        }        
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#hasWritePermission(java.lang.String, java.lang.String)
     */
    @Override
    public boolean hasWritePermission(String userId, String designId) throws StorageException {
        try {
            return this.jdbi.withHandle( handle -> {
                // Check for permissions first
                String statement = sqlStatements.hasWritePermission();
                Long did = Long.valueOf(designId);
                int count = handle.createQuery(statement)
                    .bind(0, did)
                    .bind(1, userId)
                    .mapTo(Integer.class).findOnly();
                return count == 1;
            });
        } catch (Exception e) {
            throw new StorageException("Error checking permission.", e);
        }        
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listPermissions(java.lang.String)
     */
    @Override
    public Collection<ApiDesignCollaborator> listPermissions(String designId) throws StorageException {
        logger.debug("Getting a list of all permissions (collaborators) for API: {}.", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectPermissions();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .map(ApiDesignCollaboratorRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error listing linked accounts.", e);
        }
    }

    /**
     * @see io.apicurio.hub.core.storage.IStorage#createPermission(java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public void createPermission(String designId, String userId, String permission) throws StorageException {
        logger.debug("Inserting an ACL row for: {}", designId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.insertAcl();
                Long did = Long.valueOf(designId);
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, did)
                      .bind(2, permission)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error inserting ACL row.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#deletePermission(java.lang.String, java.lang.String)
     */
    @Override
    public void deletePermission(String designId, String userId) throws StorageException {
        logger.debug("Deleting an ACL row for: {}", designId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.deleteAcl();
                Long did = Long.valueOf(designId);
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, did)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error deleting ACL row.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#updatePermission(java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public void updatePermission(String designId, String userId, String permission) throws StorageException {
        logger.debug("Updating an ACL row for: {}", designId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.updateAcl();
                Long did = Long.valueOf(designId);
                handle.createUpdate(statement)
                      .bind(0, permission)
                      .bind(1, userId)
                      .bind(2, did)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error deleting ACL row.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#createLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccount)
     */
    @Override
    public void createLinkedAccount(String userId, LinkedAccount account)
            throws AlreadyExistsException, StorageException {
        logger.debug("Inserting a Linked Account {} for {}", account.getType().name(), userId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.insertLinkedAccount();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, account.getType().name())
                      .bind(2, account.getLinkedOn())
                      .bind(3, account.getUsedOn())
                      .bind(4, account.getNonce())
                      .execute();
                return null;
            });
        } catch (Exception e) {
            if (e.getMessage().contains("Unique")) {
                throw new AlreadyExistsException();
            } else {
                throw new StorageException("Error inserting Linked Account.", e);
            }
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#getLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccountType)
     */
    @Override
    public LinkedAccount getLinkedAccount(String userId, LinkedAccountType type)
            throws StorageException, NotFoundException {
        logger.debug("Selecting a single Linked Account: {}::{}", userId, type.name());
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectLinkedAccountByType();
                return handle.createQuery(statement)
                        .bind(0, userId)
                        .bind(1, type.name())
                        .mapToBean(LinkedAccount.class)
                        .findOnly();
            });
        } catch (IllegalStateException e) {
            throw new NotFoundException();
        } catch (Exception e) {
            throw new StorageException("Error getting linked account.");
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listLinkedAccounts(java.lang.String)
     */
    @Override
    public Collection<LinkedAccount> listLinkedAccounts(String userId) throws StorageException {
        logger.debug("Getting a list of all Linked Accouts for {}.", userId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectLinkedAccounts();
                return handle.createQuery(statement)
                        .bind(0, userId)
                        .mapToBean(LinkedAccount.class)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error listing linked accounts.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#deleteLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccountType)
     */
    @Override
    public void deleteLinkedAccount(String userId, LinkedAccountType type)
            throws StorageException, NotFoundException {
        logger.debug("Deleting a Linked Account: {}::{}", userId, type.name());
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.deleteLinkedAccount();
                int rowCount = handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, type.name())
                      .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error deleting a Linked Account", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#deleteLinkedAccounts(java.lang.String)
     */
    @Override
    public void deleteLinkedAccounts(String userId) throws StorageException {
        logger.debug("Deleting all Linked Accounts for {}", userId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.deleteLinkedAccounts();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error deleting Linked Accounts", e);
        }        
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#updateLinkedAccount(java.lang.String, io.apicurio.hub.api.beans.LinkedAccount)
     */
    @Override
    public void updateLinkedAccount(String userId, LinkedAccount account) throws NotFoundException, StorageException {
        logger.debug("Updating a Linked Account: {}::{}", userId, account.getType().name());
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.updateLinkedAccount();
                int rowCount = handle.createUpdate(statement)
                        .bind(0, account.getUsedOn())
                        .bind(1, account.getLinkedOn())
                        .bind(2, account.getNonce())
                        .bind(3, userId)
                        .bind(4, account.getType().name())
                        .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error updating a Linked Account.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listContributors(java.lang.String, java.lang.String)
     */
    @Override
    public Collection<Contributor> listContributors(String userId, String designId)
            throws NotFoundException, StorageException {
        logger.debug("Selecting all contributors for API Design: {}", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiDesignContributors();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, userId)
                        .map(ConstributorRowMapper.instance)
                        .list();
            });
        } catch (IllegalStateException e) {
            throw new NotFoundException();
        } catch (Exception e) {
            throw new StorageException("Error getting contributors.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#addContent(java.lang.String, java.lang.String, io.apicurio.hub.api.beans.ApiContentType, java.lang.String)
     */
    @Override
    public long addContent(String userId, String designId, ApiContentType type, String data) throws StorageException {
        logger.debug("Inserting a 'command' content row for: {}", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                // Insert a row in the api_content table.  Retrieve the ID.
                String statement = sqlStatements.insertContent();
                CharacterStreamArgument contentClob = new CharacterStreamArgument(new StringReader(data), data.length());
                Long contentVersion = handle.createUpdate(statement)
                      .bind(0, Long.parseLong(designId))
                      .bind(1, type.getId())
                      .bind(2, contentClob)
                      .bind(3, userId)
                      .bind(4, new Date())
                      .executeAndReturnGeneratedKeys("version")
                      .mapTo(Long.class)
                      .findOnly();
                return contentVersion;
            });
        } catch (Exception e) {
            throw new StorageException("Error adding content entry for API design.", e);
        }
    }

    /**
     * @see io.apicurio.hub.core.storage.IStorage#getApiDesign(java.lang.String, java.lang.String)
     */
    @Override
    public ApiDesign getApiDesign(String userId, String designId) throws NotFoundException, StorageException {
        logger.debug("Selecting a single API Design: {}", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiDesignById();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, userId)
                        .map(ApiDesignRowMapper.instance)
                        .findOnly();
            });
        } catch (IllegalStateException e) {
            throw new NotFoundException();
        } catch (Exception e) {
            throw new StorageException("Error getting API design.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#getLatestContentDocument(java.lang.String, java.lang.String)
     */
    @Override
    public ApiDesignContent getLatestContentDocument(String userId, String designId)
            throws NotFoundException, StorageException {
        logger.debug("Selecting the most recent api_content row of type 'document' for: {}", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectLatestContentDocument();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, userId)
                        .map(ApiDesignContentRowMapper.instance)
                        .findOnly();
            });
        } catch (IllegalStateException e) {
            throw new NotFoundException();
        } catch (Exception e) {
            throw new StorageException("Error getting content document.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listContentCommands(java.lang.String, java.lang.String, long)
     */
    @Override
    public List<ApiDesignCommand> listContentCommands(String userId, String designId, long sinceVersion)
            throws StorageException {
        logger.debug("Selecting the content 'command' rows for API {} since content version {}", designId, sinceVersion);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectContentCommands();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, userId)
                        .bind(2, sinceVersion)
                        .map(ApiDesignCommandRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error getting content commands.", e);
        }
    }

    /**
     * @see io.apicurio.hub.core.storage.IStorage#createApiDesign(java.lang.String, io.apicurio.hub.api.beans.ApiDesign)
     */
    @Override
    public String createApiDesign(String userId, ApiDesign design, String initialContent) throws StorageException {
        logger.debug("Inserting an API Design: {}", design.getName());
        try {
            return this.jdbi.withHandle( handle -> {
                // Insert a row in the api_designs table first.  Retrieve the ID.
                String statement = sqlStatements.insertApiDesign();
                String designId = handle.createUpdate(statement)
                      .bind(0, design.getName())
                      .bind(1, trimTo255(design.getDescription()))
                      .bind(2, design.getCreatedBy())
                      .bind(3, design.getCreatedOn())
                      .bind(4, asCsv(design.getTags()))
                      .executeAndReturnGeneratedKeys("id")
                      .mapTo(String.class)
                      .findOnly();
                
                long did = Long.parseLong(designId);
                
                // Insert a row in the ACL table with role 'owner' for this API
                statement = sqlStatements.insertAcl();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, did)
                      .bind(2, "owner")
                      .execute();
                
                // Insert a row in the api_content table (initial value)
                statement = sqlStatements.insertContent();
                CharacterStreamArgument contentClob = new CharacterStreamArgument(new StringReader(initialContent), initialContent.length());
                handle.createUpdate(statement)
                      .bind(0, did)
                      .bind(1, ApiContentType.Document.getId())
                      .bind(2, contentClob)
                      .bind(3, userId)
                      .bind(4, design.getCreatedOn())
                      .execute();
                
                return designId;
            });
        } catch (Exception e) {
            throw new StorageException("Error inserting API design.", e);
        }
    }

    /**
     * Converts from a Set of tags to a CSV of those tags.
     * @param tags
     */
    private static String asCsv(Set<String> tags) {
        StringBuilder builder = new StringBuilder();
        tags.forEach( tag -> {
            builder.append(tag);
            builder.append(',');
        });
        if (builder.length() > 0) {
            return builder.substring(0, builder.length() - 1);
        } else {
            return null;
        }
    }

    /**
     * @see io.apicurio.hub.core.storage.IStorage#deleteApiDesign(java.lang.String, java.lang.String)
     */
    @Override
    public void deleteApiDesign(String userId, String designId) throws NotFoundException, StorageException {
        logger.debug("Deleting an API Design: {}", designId);
        try {
            this.jdbi.withHandle( handle -> {
                // Check for permissions first
                String statement = sqlStatements.hasWritePermission();
                Long did = Long.valueOf(designId);
                int count = handle.createQuery(statement)
                    .bind(0, did)
                    .bind(1, userId)
                    .mapTo(Integer.class).findOnly();
                if (count == 0) {
                    throw new NotFoundException();
                }

                // If OK then delete ACL entries
                statement = sqlStatements.clearAcl();
                handle.createUpdate(statement).bind(0, did).execute();

                // And also delete any invitations
                statement = sqlStatements.clearInvitations();
                handle.createUpdate(statement).bind(0, did).execute();

                // And also delete the api_content rows
                statement = sqlStatements.clearContent();
                handle.createUpdate(statement).bind(0, did).execute();
                
                // Then delete the api design itself
                statement = sqlStatements.deleteApiDesign();
                int rowCount = handle.createUpdate(statement)
                      .bind(0, did)
                      .bind(1, userId)
                      .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error deleting an API design.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#updateApiDesign(java.lang.String, io.apicurio.hub.api.beans.ApiDesign)
     */
    @Override
    public void updateApiDesign(String userId, ApiDesign design) throws NotFoundException, StorageException {
        logger.debug("Updating an API Design: {}", design.getId());
        try {
            this.jdbi.withHandle( handle -> {
                // Check for permissions first
                String statement = sqlStatements.hasWritePermission();
                int count = handle.createQuery(statement)
                    .bind(0, Long.valueOf(design.getId()))
                    .bind(1, userId)
                    .mapTo(Integer.class).findOnly();
                if (count == 0) {
                    throw new NotFoundException();
                }

                // Then perform the update
                statement = sqlStatements.updateApiDesign();
                int rowCount = handle.createUpdate(statement)
                        .bind(0, design.getName())
                        .bind(1, trimTo255(design.getDescription()))
                        .bind(2, asCsv(design.getTags()))
                        .bind(3, Long.valueOf(design.getId()))
                        .execute();
                if (rowCount == 0) {
                    throw new NotFoundException();
                }
                return null;
            });
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Error updating an API design.", e);
        }
    }

    /**
     * Trims the given string to a length of no more than 255 - for storing in the DB.
     * @param description
     */
    private String trimTo255(String description) {
        if (description == null || description.length() <= 255) {
            return description;
        }
        return description.substring(0, 252) + "...";
    }

    /**
     * @see io.apicurio.hub.core.storage.IStorage#listApiDesigns(java.lang.String)
     */
    @Override
    public Collection<ApiDesign> listApiDesigns(String userId) throws StorageException {
        logger.debug("Getting a list of all API designs.");
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiDesigns();
                return handle.createQuery(statement)
                        .bind(0, userId)
                        .map(ApiDesignRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error listing API designs.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#createEditingSessionUuid(java.lang.String, java.lang.String, java.lang.String, java.lang.String, long, long)
     */
    @Override
    public void createEditingSessionUuid(String uuid, String designId, String userId, String hash, long contentVersion,
            long expiresOn) throws StorageException {
        logger.debug("Inserting an Editing Session UUID row: {}", uuid);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.insertEditingSessionUuid();
                handle.createUpdate(statement)
                      .bind(0, uuid)
                      .bind(1, Long.valueOf(designId))
                      .bind(2, userId)
                      .bind(3, hash)
                      .bind(4, contentVersion)
                      .bind(5, expiresOn)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error inserting editing session UUID row.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#lookupEditingSessionUuid(java.lang.String, java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public long lookupEditingSessionUuid(String uuid, String designId, String userId, String hash)
            throws StorageException {
        logger.debug("Looking up an editing session UUID: {}", uuid);
        long now = System.currentTimeMillis();
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectEditingSessionUuid();
                Long contentVersion = handle.createQuery(statement)
                        .bind(0, uuid)
                        .bind(1, Long.valueOf(designId))
                        .bind(2, hash)
                        .bind(3, now)
                        .map(new RowMapper<Long>() {
                            @Override
                            public Long map(ResultSet rs, StatementContext ctx) throws SQLException {
                                return rs.getLong("version");
                            }
                        })
                        .findOnly();
                return contentVersion;
            });
        } catch (Exception e) {
            throw new StorageException("Error getting Editing Session UUID.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#consumeEditingSessionUuid(java.lang.String, java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public boolean consumeEditingSessionUuid(String uuid, String designId, String userId, String hash)
            throws StorageException {
        logger.debug("Consuming/Deleting an editing session UUID: {}", uuid);
        long now = System.currentTimeMillis();
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.deleteEditingSessionUuid();
                int rowCount = handle.createUpdate(statement)
                        .bind(0, uuid)
                        .bind(1, Long.valueOf(designId))
                        .bind(2, hash)
                        .bind(3, now)
                        .execute();
                if (rowCount == 0) {
                    return false;
                }
                return true;
            });
        } catch (Exception e) {
            throw new StorageException("Error deleting a Linked Account", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#createCollaborationInvite(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public void createCollaborationInvite(String inviteId, String designId, String userId, String username, String role,
            String subject) throws StorageException {
        logger.debug("Inserting a collaboration invitation row: {}  for design: {}", inviteId, designId);
        try {
            this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.insertCollaborationInvitation();
                handle.createUpdate(statement)
                      .bind(0, userId)
                      .bind(1, new Date())
                      .bind(2, username)
                      .bind(3, Long.valueOf(designId))
                      .bind(4, role)
                      .bind(5, inviteId)
                      .bind(6, "pending")
                      .bind(7, subject)
                      .execute();
                return null;
            });
        } catch (Exception e) {
            throw new StorageException("Error inserting editing session UUID row.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#updateCollaborationInviteStatus(java.lang.String, java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public boolean updateCollaborationInviteStatus(String inviteId, String fromStatus, String toStatus, String userId)
            throws StorageException {
        logger.debug("Updating the status of an invitation: {}  from: {}  to: {}", inviteId, fromStatus, toStatus);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.updateCollaborationInvitationStatus();
                int rowCount = handle.createUpdate(statement)
                        .bind(0, toStatus)
                        .bind(1, userId)
                        .bind(2, new Date())
                        .bind(3, inviteId)
                        .bind(4, fromStatus)
                        .execute();
                if (rowCount == 0) {
                    return false;
                }
                return true;
            });
        } catch (Exception e) {
            throw new StorageException("Error updating an invitation status.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listCollaborationInvites(java.lang.String, java.lang.String)
     */
    @Override
    public List<Invitation> listCollaborationInvites(String designId, String userId) throws StorageException {
        logger.debug("Selecting all invitations for API Design: {}", designId);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectCollaborationInvitations();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, userId)
                        .map(InvitationRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error getting invitations.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#getCollaborationInvite(java.lang.String, java.lang.String)
     */
    @Override
    public Invitation getCollaborationInvite(String designId, String inviteId)
            throws StorageException, NotFoundException {
        logger.debug("Selecting a single invitation for API Design: {}  with inviteId: {}", designId, inviteId);
        try {
            return (Invitation) this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectCollaborationInvitation();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, inviteId)
                        .map(InvitationRowMapper.instance)
                        .findOnly();
            });
        } catch (Exception e) {
            throw new StorageException("Error getting invitations.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listApiDesignActivity(java.lang.String, int, int)
     */
    @Override
    public Collection<ApiDesignChange> listApiDesignActivity(String designId, int from, int to) throws StorageException {
        logger.debug("Selecting activity for API Design: {} from {} to {}", designId, from, to);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiDesignActivity();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, to - from)
                        .bind(2, from)
                        .map(ApiDesignChangeRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error getting contributors.", e);
        }
    }
    
    /**
     * @see io.apicurio.hub.core.storage.IStorage#listUserActivity(java.lang.String, int, int)
     */
    @Override
    public Collection<ApiDesignChange> listUserActivity(String user, int from, int to) throws StorageException {
        logger.debug("Selecting activity for User: {} from {} to {}", user, from, to);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectUserActivity();
                return handle.createQuery(statement)
                        .bind(0, user)
                        .bind(1, to - from)
                        .bind(2, from)
                        .map(ApiDesignChangeRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error getting contributors.", e);
        }
    }

    /**
     * @see io.apicurio.hub.core.storage.IStorage#listApiDesignPublications(java.lang.String, int, int)
     */
    @Override
    public Collection<ApiPublication> listApiDesignPublications(String designId, int from, int to) throws StorageException {
        logger.debug("Selecting publication activity for API Design: {} from {} to {}", designId, from, to);
        try {
            return this.jdbi.withHandle( handle -> {
                String statement = sqlStatements.selectApiPublicationActivity();
                return handle.createQuery(statement)
                        .bind(0, Long.valueOf(designId))
                        .bind(1, to - from)
                        .bind(2, from)
                        .map(ApiPublicationRowMapper.instance)
                        .list();
            });
        } catch (Exception e) {
            throw new StorageException("Error getting contributors.", e);
        }
    }

    
    /**
     * A row mapper to read an api design from the DB (as a single row in a SELECT)
     * and return an ApiDesign instance.
     * @author eric.wittmann@gmail.com
     */
    private static class ApiDesignRowMapper implements RowMapper<ApiDesign> {
        
        public static final ApiDesignRowMapper instance = new ApiDesignRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public ApiDesign map(ResultSet rs, StatementContext ctx) throws SQLException {
            ApiDesign design = new ApiDesign();
            design.setId(rs.getString("id"));
            design.setName(rs.getString("name"));
            design.setDescription(rs.getString("description"));
            design.setCreatedBy(rs.getString("created_by"));
            design.setCreatedOn(rs.getTimestamp("created_on"));
            String tags = rs.getString("tags");
            design.getTags().addAll(toSet(tags));
            return design;
        }

        /**
         * Read CSV data and convert to a set of strings.
         * @param tags
         */
        private Set<String> toSet(String tags) {
            Set<String> rval = new HashSet<String>();
            if (tags != null && tags.length() > 0) {
                String[] split = tags.split(",");
                for (String tag : split) {
                    rval.add(tag.trim());
                }
            }
            return rval;
        }

    }

    /**
     * A row mapper to read contributor information from a result set.  Each row in 
     * the result set must have a 'created_by' column and an 'edits' column.
     * @author eric.wittmann@gmail.com
     */
    private static class ConstributorRowMapper implements RowMapper<Contributor> {
        
        public static final ConstributorRowMapper instance = new ConstributorRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public Contributor map(ResultSet rs, StatementContext ctx) throws SQLException {
            Contributor contributor = new Contributor();
            contributor.setName(rs.getString("created_by"));
            contributor.setEdits(rs.getInt("edits"));
            return contributor;
        }

    }

    /**
     * A row mapper to read a 'document' style row from the api_content table.
     * @author eric.wittmann@gmail.com
     */
    private static class ApiDesignContentRowMapper implements RowMapper<ApiDesignContent> {
        
        public static final ApiDesignContentRowMapper instance = new ApiDesignContentRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public ApiDesignContent map(ResultSet rs, StatementContext ctx) throws SQLException {
            try {
                ApiDesignContent content = new ApiDesignContent();
                content.setContentVersion(rs.getLong("version"));
                content.setOaiDocument(IOUtils.toString(rs.getCharacterStream("data")));
                return content;
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }

    }

    /**
     * A row mapper to read a 'document' style row from the api_content table.
     * @author eric.wittmann@gmail.com
     */
    private static class ApiDesignCommandRowMapper implements RowMapper<ApiDesignCommand> {
        
        public static final ApiDesignCommandRowMapper instance = new ApiDesignCommandRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public ApiDesignCommand map(ResultSet rs, StatementContext ctx) throws SQLException {
            try {
                ApiDesignCommand content = new ApiDesignCommand();
                content.setContentVersion(rs.getLong("version"));
                content.setCommand(IOUtils.toString(rs.getCharacterStream("data")));
                return content;
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }

    }

    /**
     * A row mapper to read an invitation from a db row.
     * @author eric.wittmann@gmail.com
     */
    private static class InvitationRowMapper implements RowMapper<Invitation> {
        
        public static final InvitationRowMapper instance = new InvitationRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public Invitation map(ResultSet rs, StatementContext ctx) throws SQLException {
            Invitation invite = new Invitation();
            invite.setCreatedBy(rs.getString("created_by"));
            invite.setCreatedOn(rs.getDate("created_on"));
            invite.setDesignId(rs.getString("design_id"));
            invite.setInviteId(rs.getString("invite_id"));
            invite.setModifiedBy(rs.getString("modified_by"));
            invite.setModifiedOn(rs.getDate("modified_on"));
            invite.setStatus(rs.getString("status"));
            invite.setRole(rs.getString("role"));
            invite.setSubject(rs.getString("subject"));
            return invite;
        }

    }

    /**
     * A row mapper to read an collaborator from a db row.
     * @author eric.wittmann@gmail.com
     */
    private static class ApiDesignCollaboratorRowMapper implements RowMapper<ApiDesignCollaborator> {
        
        public static final ApiDesignCollaboratorRowMapper instance = new ApiDesignCollaboratorRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public ApiDesignCollaborator map(ResultSet rs, StatementContext ctx) throws SQLException {
            ApiDesignCollaborator collaborator = new ApiDesignCollaborator();
            collaborator.setUserName(rs.getString("user_id"));
            collaborator.setUserId(rs.getString("user_id"));
            collaborator.setRole(rs.getString("role"));
            return collaborator;
        }

    }

    /**
     * A row mapper to read a single row from the content db as an {@link ApiDesignChange}.
     * @author eric.wittmann@gmail.com
     */
    private static class ApiDesignChangeRowMapper implements RowMapper<ApiDesignChange> {
        
        public static final ApiDesignChangeRowMapper instance = new ApiDesignChangeRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public ApiDesignChange map(ResultSet rs, StatementContext ctx) throws SQLException {
            try {
                ApiDesignChange change = new ApiDesignChange();
                change.setApiId(rs.getString("design_id"));
                change.setApiName(rs.getString("name"));
                change.setBy(rs.getString("created_by"));
                change.setData(IOUtils.toString(rs.getCharacterStream("data")));
                change.setOn(rs.getDate("created_on"));
                change.setType(ApiContentType.fromId(rs.getInt("type")));
                change.setVersion(rs.getLong("version"));
                return change;
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }

    }

    /**
     * A row mapper to read a single row from the content db as an {@link ApiDesignChange}.
     * @author eric.wittmann@gmail.com
     */
    private static class ApiPublicationRowMapper implements RowMapper<ApiPublication> {
        
        public static final ApiPublicationRowMapper instance = new ApiPublicationRowMapper();

        /**
         * @see org.jdbi.v3.core.mapper.RowMapper#map(java.sql.ResultSet, org.jdbi.v3.core.statement.StatementContext)
         */
        @Override
        public ApiPublication map(ResultSet rs, StatementContext ctx) throws SQLException {
            try {
                ApiPublication change = new ApiPublication();
                change.setBy(rs.getString("created_by"));
                change.setInfo(IOUtils.toString(rs.getCharacterStream("data")));
                change.setOn(rs.getDate("created_on"));
                return change;
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }

    }

}
