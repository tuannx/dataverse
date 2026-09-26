package edu.harvard.iq.dataverse.util.json;

import edu.harvard.iq.dataverse.*;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.branding.BrandingUtil;
import edu.harvard.iq.dataverse.util.SystemConfig;

import edu.harvard.iq.dataverse.util.json.JsonUtil;
import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import jakarta.json.JsonException;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static edu.harvard.iq.dataverse.dataset.DatasetUtil.getLocaleCurationStatusLabel;
import static edu.harvard.iq.dataverse.util.json.JsonPrinter.jsonRoleAssignments;

/**
 * A helper class to build a JSON representation of a UserNotification.
 * <p>
 * It is responsible for adding the correct fields to a JSON object based on the
 * notification type.
 */
@Stateless
public class InAppNotificationsJsonPrinter {

    public static final String KEY_ROLE_ASSIGNMENTS = "roleAssignments";
    public static final String KEY_DATAVERSE_ALIAS = "dataverseAlias";
    public static final String KEY_DATAVERSE_DISPLAY_NAME = "dataverseDisplayName";
    public static final String KEY_DATASET_PERSISTENT_ID = "datasetPersistentIdentifier";
    public static final String KEY_DATASET_DISPLAY_NAME = "datasetDisplayName";
    public static final String KEY_OWNER_PERSISTENT_ID = "ownerPersistentIdentifier";
    public static final String KEY_OWNER_ALIAS = "ownerAlias";
    public static final String KEY_OWNER_DISPLAY_NAME = "ownerDisplayName";
    public static final String KEY_REQUESTOR_FIRST_NAME = "requestorFirstName";
    public static final String KEY_REQUESTOR_LAST_NAME = "requestorLastName";
    public static final String KEY_REQUESTOR_EMAIL = "requestorEmail";
    public static final String KEY_DATAFILE_ID = "dataFileId";
    public static final String KEY_DATAFILE_DISPLAY_NAME = "dataFileDisplayName";
    public static final String KEY_GUIDES_BASE_URL = "userGuidesBaseUrl";
    public static final String KEY_GUIDES_VERSION = "userGuidesVersion";
    public static final String KEY_GUIDES_SECTION_PATH = "userGuidesSectionPath";
    public static final String KEY_CURATION_STATUS = "currentCurationStatus";
    public static final String KEY_ADDITIONAL_INFO = "additionalInfo";
    public static final String KEY_OBJECT_DELETED = "objectDeleted";
    public static final String KEY_INSTALLATION_BRAND_NAME = "installationBrandName";

    public static final String GUIDES_SECTION_PATH_DATAVERSE_MANAGEMENT_HTML = "user/dataverse-management.html";
    public static final String GUIDES_SECTION_PATH_DATASET_MANAGEMENT_HTML = "user/dataset-management.html";
    public static final String GUIDES_SECTION_PATH_DATASET_MANAGEMENT_TABULAR_FILES_HTML = "user/dataset-management.html#tabular-data-files";
    public static final String GUIDES_SECTION_PATH_USER_HTML = "user/index.html";

    @EJB
    private DatasetServiceBean datasetService;
    @EJB
    private DatasetVersionServiceBean datasetVersionService;
    @EJB
    private DataFileServiceBean dataFileService;
    @EJB
    private DvObjectServiceBean dvObjectService;
    @EJB
    private PermissionServiceBean permissionService;
    @EJB
    private SystemConfig systemConfig;

    /**
     * Display data preloaded for a page of notifications: referenced objects by
     * id, plus a per-user cache of effective role assignments. Pass the same
     * instance to every {@link #addFieldsByType} call of one page render so
     * each object and each assignment set loads once.
     */
    public static class NotificationPreload {
        private final Map<Long, DvObject> dvObjects = new HashMap<>();
        private final Map<Long, DatasetVersion> versions = new HashMap<>();
        private final Map<String, List<RoleAssignment>> assignmentsByUserAndObject = new HashMap<>();
    }

    /**
     * Preloads every object referenced by the given notifications with a fixed
     * handful of {@code IN} queries (dataverse objects, versions, display
     * graphs), replacing one query per notification per object.
     */
    public NotificationPreload preload(List<UserNotification> notifications) {
        NotificationPreload preload = new NotificationPreload();
        Set<Long> dvObjectIds = new HashSet<>();
        Set<Long> fileIds = new HashSet<>();
        Set<Long> versionIds = new HashSet<>();
        for (UserNotification notification : notifications) {
            if (notification.getObjectId() == null) {
                continue;
            }
            switch (notification.getType()) {
                case CREATEDS:
                case SUBMITTEDDS:
                case PUBLISHEDDS:
                case PUBLISHFAILED_PIDREG:
                case RETURNEDDS:
                case WORKFLOW_SUCCESS:
                case WORKFLOW_FAILURE:
                case PIDRECONCILED:
                case FILESYSTEMIMPORT:
                case CHECKSUMIMPORT:
                case STATUSUPDATED:
                    versionIds.add(notification.getObjectId());
                    break;
                case REQUESTFILEACCESS:
                case REQUESTEDFILEACCESS:
                    fileIds.add(notification.getObjectId());
                    break;
                case CREATEACC:
                    break;
                default:
                    dvObjectIds.add(notification.getObjectId());
                    break;
            }
        }
        Map<Long, Dataset> datasets = new HashMap<>();
        List<DataFile> files = new ArrayList<>();
        for (DvObject dvObject : dvObjectService.findDvObjectsByIds(dvObjectIds)) {
            preload.dvObjects.put(dvObject.getId(), dvObject);
            if (dvObject instanceof Dataset) {
                datasets.put(dvObject.getId(), (Dataset) dvObject);
            } else if (dvObject instanceof DataFile) {
                files.add((DataFile) dvObject);
            }
        }
        for (DataFile dataFile : dataFileService.findFilesByIds(fileIds)) {
            preload.dvObjects.put(dataFile.getId(), dataFile);
            files.add(dataFile);
        }
        for (DatasetVersion version : datasetVersionService.findVersionsByIds(versionIds)) {
            preload.versions.put(version.getId(), version);
            datasets.put(version.getDataset().getId(), version.getDataset());
        }
        datasetService.preloadDisplayGraphs(datasets.values());
        dataFileService.preloadFileMetadatas(files);
        return preload;
    }

    /**
     * Populates a JSON builder with fields specific to the notification type.
     *
     * @param notificationJson  The JSON builder to add fields to.
     * @param authenticatedUser The user receiving the notification.
     * @param userNotification  The notification object containing the details.
     */
    public void addFieldsByType(final NullSafeJsonBuilder notificationJson, final AuthenticatedUser authenticatedUser, final UserNotification userNotification) {
        addFieldsByType(notificationJson, authenticatedUser, userNotification,
                preload(Collections.singletonList(userNotification)));
    }

    /**
     * Adds type-specific fields resolving every referenced object from the
     * given {@link NotificationPreload} instead of issuing one query per
     * notification. Callers rendering a page must preload once for the whole
     * list and pass the same instance to every call.
     */
    public void addFieldsByType(final NullSafeJsonBuilder notificationJson, final AuthenticatedUser authenticatedUser,
            final UserNotification userNotification, final NotificationPreload preload) {
        final AuthenticatedUser requestor = userNotification.getRequestor();

        switch (userNotification.getType()) {
            case ASSIGNROLE:
            case REVOKEROLE:
                addRoleFields(notificationJson, authenticatedUser, userNotification, preload);
                break;
            case CREATEDV:
                addCreateDataverseFields(notificationJson, userNotification, preload);
                break;
            case REQUESTFILEACCESS:
                addRequestFileAccessFields(notificationJson, userNotification, requestor, preload);
                break;
            case REQUESTEDFILEACCESS:
                addDataFileFields(notificationJson, userNotification, preload);
                break;
            case DATASETCREATED:
                addDatasetCreatedFields(notificationJson, userNotification, requestor, preload);
                break;
            case CREATEDS:
                addCreateDatasetFields(notificationJson, userNotification, preload);
                break;
            case SUBMITTEDDS:
                addSubmittedDatasetFields(notificationJson, userNotification, requestor, preload);
                break;
            case PUBLISHEDDS:
            case PUBLISHFAILED_PIDREG:
            case RETURNEDDS:
            case WORKFLOW_SUCCESS:
            case WORKFLOW_FAILURE:
            case PIDRECONCILED:
            case FILESYSTEMIMPORT:
            case CHECKSUMIMPORT:
                addDatasetVersionFields(notificationJson, userNotification, preload);
                break;
            case STATUSUPDATED:
                addDatasetVersionFields(notificationJson, userNotification, preload, true);
                break;
            case CREATEACC:
                addCreateAccountFields(notificationJson);
                break;
            case GLOBUSUPLOADCOMPLETED:
            case GLOBUSDOWNLOADCOMPLETED:
            case GLOBUSUPLOADCOMPLETEDWITHERRORS:
            case GLOBUSUPLOADREMOTEFAILURE:
            case GLOBUSUPLOADLOCALFAILURE:
            case GLOBUSDOWNLOADCOMPLETEDWITHERRORS:
            case CHECKSUMFAIL:
            case GRANTFILEACCESS:
            case REJECTFILEACCESS:
                addDatasetFields(notificationJson, userNotification, preload);
                break;
            case INGESTCOMPLETED:
            case INGESTCOMPLETEDWITHERRORS:
                addIngestFields(notificationJson, userNotification, preload);
                break;
            case DATASETMENTIONED:
                addDatasetMentionedFields(notificationJson, userNotification, preload);
                break;
            case DATASETMOVED:
                addDatasetMovedFields(notificationJson, userNotification, requestor, preload);
                break;
        }
    }

    private void addRoleFields(final NullSafeJsonBuilder notificationJson, final AuthenticatedUser authenticatedUser,
            final UserNotification userNotification, final NotificationPreload preload) {
        DvObject dvObject = preload.dvObjects.get(userNotification.getObjectId());
        if (dvObject instanceof Dataverse) {
            Dataverse dataverse = (Dataverse) dvObject;
            notificationJson.add(KEY_ROLE_ASSIGNMENTS, jsonRoleAssignments(cachedAssignments(authenticatedUser, dataverse, preload)));
            notificationJson.add(KEY_DATAVERSE_ALIAS, dataverse.getAlias());
            notificationJson.add(KEY_DATAVERSE_DISPLAY_NAME, dataverse.getDisplayName());
        } else if (dvObject instanceof Dataset) {
            Dataset dataset = (Dataset) dvObject;
            notificationJson.add(KEY_ROLE_ASSIGNMENTS, jsonRoleAssignments(cachedAssignments(authenticatedUser, dataset, preload)));
            notificationJson.add(KEY_DATASET_PERSISTENT_ID, dataset.getGlobalId().asString());
            notificationJson.add(KEY_DATASET_DISPLAY_NAME, dataset.getDisplayName());
        } else if (dvObject instanceof DataFile) {
            DataFile datafile = (DataFile) dvObject;
            notificationJson.add(KEY_ROLE_ASSIGNMENTS, jsonRoleAssignments(cachedAssignments(authenticatedUser, datafile, preload)));
            notificationJson.add(KEY_OWNER_PERSISTENT_ID, datafile.getOwner().getGlobalId().asString());
            notificationJson.add(KEY_OWNER_DISPLAY_NAME, datafile.getOwner().getDisplayName());
        } else {
            notificationJson.add(KEY_OBJECT_DELETED, true);
        }
    }

    private List<RoleAssignment> cachedAssignments(final AuthenticatedUser authenticatedUser, final DvObject dvObject,
            final NotificationPreload preload) {
        String key = authenticatedUser.getId() + ":" + dvObject.getId();
        List<RoleAssignment> cached = preload.assignmentsByUserAndObject.get(key);
        if (cached == null) {
            cached = permissionService.getEffectiveRoleAssignments(authenticatedUser, dvObject);
            preload.assignmentsByUserAndObject.put(key, cached);
        }
        return cached;
    }

    private void addCreateDataverseFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        DvObject dvObject = preload.dvObjects.get(userNotification.getObjectId());
        if (dvObject instanceof Dataverse) {
            final Dataverse dataverse = (Dataverse) dvObject;
            notificationJson.add(KEY_DATAVERSE_ALIAS, dataverse.getAlias());
            notificationJson.add(KEY_DATAVERSE_DISPLAY_NAME, dataverse.getDisplayName());
            Dataverse owner = dataverse.getOwner();
            if (owner != null) {
                notificationJson.add(KEY_OWNER_ALIAS, owner.getAlias());
                notificationJson.add(KEY_OWNER_DISPLAY_NAME, owner.getDisplayName());
            }
        } else {
            notificationJson.add(KEY_OBJECT_DELETED, true);
        }
        addGuidesFields(notificationJson, GUIDES_SECTION_PATH_DATAVERSE_MANAGEMENT_HTML);
    }

    private void addCreateAccountFields(final NullSafeJsonBuilder notificationJson) {
        notificationJson.add(KEY_INSTALLATION_BRAND_NAME, BrandingUtil.getInstallationBrandName());
        addGuidesFields(notificationJson, GUIDES_SECTION_PATH_USER_HTML);
    }

    private void addRequestFileAccessFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final AuthenticatedUser requestor, final NotificationPreload preload) {
        addRequestorFields(notificationJson, requestor);
        addDataFileFields(notificationJson, userNotification, preload);
    }

    private void addDataFileFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        DvObject dvObject = preload.dvObjects.get(userNotification.getObjectId());
        if (dvObject instanceof DataFile) {
            final DataFile dataFile = (DataFile) dvObject;
            notificationJson.add(KEY_DATAFILE_ID, dataFile.getId());
            notificationJson.add(KEY_DATAFILE_DISPLAY_NAME, dataFile.getDisplayName());
            notificationJson.add(KEY_DATASET_DISPLAY_NAME, dataFile.getOwner().getDisplayName());
            notificationJson.add(KEY_DATASET_PERSISTENT_ID, dataFile.getOwner().getGlobalId().asString());
        } else {
            notificationJson.add(KEY_OBJECT_DELETED, true);
        }
    }

    private void addDatasetCreatedFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final AuthenticatedUser requestor, final NotificationPreload preload) {
        addDatasetFields(notificationJson, userNotification, preload);
        addRequestorFields(notificationJson, requestor);
    }

    private void addRequestorFields(final NullSafeJsonBuilder notificationJson, final AuthenticatedUser requestor) {
        if (requestor != null) {
            notificationJson.add(KEY_REQUESTOR_FIRST_NAME, requestor.getFirstName());
            notificationJson.add(KEY_REQUESTOR_LAST_NAME, requestor.getLastName());
            notificationJson.add(KEY_REQUESTOR_EMAIL, requestor.getEmail());
        }
    }

    private void addDatasetFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        DvObject dvObject = preload.dvObjects.get(userNotification.getObjectId());
        if (dvObject instanceof Dataset) {
            final Dataset dataset = (Dataset) dvObject;
            notificationJson.add(KEY_DATASET_PERSISTENT_ID, dataset.getGlobalId().asString());
            notificationJson.add(KEY_DATASET_DISPLAY_NAME, dataset.getDisplayName());
            notificationJson.add(KEY_OWNER_ALIAS, dataset.getOwner().getAlias());
            notificationJson.add(KEY_OWNER_DISPLAY_NAME, dataset.getOwner().getDisplayName());
        } else {
            notificationJson.add(KEY_OBJECT_DELETED, true);
        }
    }

    private void addCreateDatasetFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        addGuidesFields(notificationJson, GUIDES_SECTION_PATH_DATASET_MANAGEMENT_HTML);
        addDatasetVersionFields(notificationJson, userNotification, preload);
    }

    private void addGuidesFields(final NullSafeJsonBuilder notificationJson, String guidesSectionPath) {
        notificationJson.add(KEY_GUIDES_BASE_URL, systemConfig.getGuidesBaseUrl(false));
        notificationJson.add(KEY_GUIDES_VERSION, systemConfig.getGuidesVersion());

        if (guidesSectionPath != null) {
            notificationJson.add(KEY_GUIDES_SECTION_PATH, guidesSectionPath);
        }
    }

    private void addSubmittedDatasetFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final AuthenticatedUser requestor, final NotificationPreload preload) {
        addDatasetVersionFields(notificationJson, userNotification, preload);
        addRequestorFields(notificationJson, requestor);
    }

    private void addDatasetVersionFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        addDatasetVersionFields(notificationJson, userNotification, preload, false);
    }

    private void addDatasetVersionFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload, final boolean addCurationStatus) {
        final DatasetVersion datasetVersion = preload.versions.get(userNotification.getObjectId());
        if (datasetVersion != null) {
            Dataset dataset = datasetVersion.getDataset();
            notificationJson.add(KEY_DATASET_PERSISTENT_ID, dataset.getGlobalId().asString());
            notificationJson.add(KEY_DATASET_DISPLAY_NAME, dataset.getDisplayName());
            notificationJson.add(KEY_OWNER_ALIAS, dataset.getOwner().getAlias());
            notificationJson.add(KEY_OWNER_DISPLAY_NAME, dataset.getOwner().getDisplayName());
            if (addCurationStatus) {
                notificationJson.add(KEY_CURATION_STATUS, getLocaleCurationStatusLabel(datasetVersion.getCurrentCurationStatus()));
            }
        } else {
            notificationJson.add(KEY_OBJECT_DELETED, true);
        }
    }

    private void addIngestFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        addDatasetFields(notificationJson, userNotification, preload);
        addGuidesFields(notificationJson, GUIDES_SECTION_PATH_DATASET_MANAGEMENT_TABULAR_FILES_HTML);
    }

    private void addDatasetMentionedFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final NotificationPreload preload) {
        addDatasetFields(notificationJson, userNotification, preload);

        final String additionalInfo = userNotification.getAdditionalInfo();

        if (additionalInfo != null && !additionalInfo.isEmpty()) {
            try {
                // Try to parse the string into a JSON value
                JsonValue additionalInfoJson = JsonUtil.getJsonValue(additionalInfo);

                // If successful, add the parsed JSON value.
                notificationJson.add(KEY_ADDITIONAL_INFO, additionalInfoJson);

            } catch (JsonException e) {
                // If parsing fails, it's not a valid JSON string.
                // Fall back to adding it as a simple string.
                notificationJson.add(KEY_ADDITIONAL_INFO, additionalInfo);
            }
        }
    }

    private void addDatasetMovedFields(final NullSafeJsonBuilder notificationJson, final UserNotification userNotification,
            final AuthenticatedUser requestor, final NotificationPreload preload) {
        addDatasetFields(notificationJson, userNotification, preload);
        addRequestorFields(notificationJson, requestor);
    }
}
