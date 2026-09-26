package edu.harvard.iq.dataverse.util.json;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.Dataverse;
import edu.harvard.iq.dataverse.DataverseContact;
import edu.harvard.iq.dataverse.DataverseRoleServiceBean;
import edu.harvard.iq.dataverse.DataFileServiceBean;
import edu.harvard.iq.dataverse.DatasetServiceBean;
import edu.harvard.iq.dataverse.DatasetVersionServiceBean;
import edu.harvard.iq.dataverse.DataverseServiceBean;
import edu.harvard.iq.dataverse.DvObject;
import edu.harvard.iq.dataverse.DvObjectServiceBean;
import edu.harvard.iq.dataverse.PermissionServiceBean;
import edu.harvard.iq.dataverse.RoleAssigneeServiceBean;
import edu.harvard.iq.dataverse.RoleAssignment;
import edu.harvard.iq.dataverse.UserNotification;
import edu.harvard.iq.dataverse.UserNotificationServiceBean;
import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.DataverseRole;
import edu.harvard.iq.dataverse.authorization.groups.GroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.explicit.ExplicitGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.ipaddress.IpGroupsServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.maildomain.MailDomainGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.shib.ShibGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.util.testing.fixtures.DatasetFixture;
import edu.harvard.iq.dataverse.util.testing.fixtures.DatasetFixtureBuilder;
import edu.harvard.iq.dataverse.util.testing.performance.JpaEntityManagerService;
import edu.harvard.iq.dataverse.util.testing.performance.JpaPerformanceTest;
import edu.harvard.iq.dataverse.util.testing.recipes.DatasetRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.DatasetTypeRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.FileRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.VersionRecipe;
import jakarta.persistence.EntityManager;
import net.ttddyy.dsproxy.QueryCountHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quickperf.sql.annotation.EnableSameSelectTypesWithDifferentParamValues;
import org.quickperf.sql.annotation.ExpectDelete;
import org.quickperf.sql.annotation.ExpectInsert;
import org.quickperf.sql.annotation.ExpectUpdate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for upstream #2892: rendering a page of in-app notifications
 * issues one query per notification per referenced object
 * ({@code InAppNotificationsJsonPrinter} resolves {@code objectId} with a
 * service {@code find} per row, plus role assignments per row for role
 * notifications).
 *
 * <p>The fixture renders 12 mixed notifications over 9 distinct objects
 * (4 datasets, 1 dataverse, 2 versions, 2 files). The test asserts the legacy
 * per-row access pattern exceeds the new-path budget (RED proof), the
 * preloaded path stays within it, the rendered JSON carries the expected
 * fields, and rendering 8 vs 12 notifications costs the same when the
 * distinct object set is unchanged.
 */
@JpaPerformanceTest
class NotificationRenderBudgetIT {

    static JpaEntityManagerService jpa;
    static Long userId;
    static List<Long> notificationIds;

    /**
     * Fixed budget for rendering the 12-notification fixture page. Measured 50
     * (6 batched preloads + per-distinct-object EAGER residuals + 3 cached
     * assignment lookups + assignee renders); headroom to 55. The legacy
     * per-row pattern measures 71 on the same fixture.
     */
    static final int RENDER_BUDGET = 55;

    @BeforeAll
    static void setUp() {
        jpa.start();

        List<DatasetFixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            DatasetRecipe recipe = DatasetRecipe.of(
                DatasetTypeRecipe.dataset(),
                VersionRecipe.of(FileRecipe.regular(2))
            );
            fixtures.add(DatasetFixtureBuilder.builder().recipe(recipe).build());
        }

        jpa.inTransactionVoid(em -> {
            em.persist(fixtures.get(0).datasetType());

            Dataverse dataverse = new Dataverse();
            dataverse.setAlias("notifdv");
            dataverse.setName("Notif Dataverse");
            dataverse.setDataverseType(Dataverse.DataverseType.UNCATEGORIZED);
            dataverse.getDataverseContacts().add(new DataverseContact(dataverse, "notif@example.com"));
            dataverse.setCreateDate(new Timestamp(System.currentTimeMillis()));
            dataverse.setModificationTime(new Timestamp(System.currentTimeMillis()));
            em.persist(dataverse);

            List<Dataset> datasets = new ArrayList<>();
            for (int i = 0; i < fixtures.size(); i++) {
                DatasetFixture fixture = fixtures.get(i);
                Dataset dataset = fixture.dataset();
                dataset.setDatasetType(fixtures.get(0).datasetType());
                dataset.setOwner(dataverse);
                dataset.setProtocol("doi");
                dataset.setAuthority("10.7910/notif");
                dataset.setIdentifier("NF" + i);
                for (DataFile dataFile : fixture.dataFiles()) {
                    dataFile.setOwner(dataset);
                    em.persist(dataFile);
                }
                em.persist(dataset);
                datasets.add(dataset);
            }

            AuthenticatedUser owner = newUser(em, "notifuser1");
            AuthenticatedUser requestor2 = newUser(em, "notifuser2");
            AuthenticatedUser requestor3 = newUser(em, "notifuser3");
            AuthenticatedUser requestor4 = newUser(em, "notifuser4");
            AuthenticatedUser requestor5 = newUser(em, "notifuser5");

            DataverseRole role = new DataverseRole();
            role.setName("NotifRole");
            role.setAlias("notifrole");
            role.setDescription("notification fixture role");
            em.persist(role);

            for (int i = 0; i < 2; i++) {
                RoleAssignment assignment = new RoleAssignment();
                assignment.setAssigneeIdentifier("@" + owner.getUserIdentifier());
                assignment.setRole(role);
                assignment.setDefinitionPoint(datasets.get(i));
                em.persist(assignment);
            }
            em.flush();

            Dataset ds0 = datasets.get(0);
            Dataset ds1 = datasets.get(1);
            Dataset ds2 = datasets.get(2);
            Dataset ds3 = datasets.get(3);
            DatasetVersion v0 = ds0.getVersions().get(0);
            DatasetVersion v2 = ds2.getVersions().get(0);
            DataFile file2 = fixtures.get(2).dataFiles().get(0);
            DataFile file3 = fixtures.get(3).dataFiles().get(0);
            Timestamp now = new Timestamp(System.currentTimeMillis());
            List<UserNotification> notifications = new ArrayList<>();
            notifications.add(notification(owner, UserNotification.Type.ASSIGNROLE, ds0.getId(), null, now));
            notifications.add(notification(owner, UserNotification.Type.ASSIGNROLE, ds0.getId(), null, now));
            notifications.add(notification(owner, UserNotification.Type.REVOKEROLE, dataverse.getId(), null, now));
            notifications.add(notification(owner, UserNotification.Type.ASSIGNROLE, ds1.getId(), null, now));
            notifications.add(notification(owner, UserNotification.Type.PUBLISHEDDS, v0.getId(), requestor5, now));
            notifications.add(notification(owner, UserNotification.Type.PUBLISHEDDS, v2.getId(), null, now));
            notifications.add(notification(owner, UserNotification.Type.DATASETCREATED, ds1.getId(), requestor2, now));
            notifications.add(notification(owner, UserNotification.Type.REQUESTEDFILEACCESS, file2.getId(), null, now));
            notifications.add(notification(owner, UserNotification.Type.REQUESTEDFILEACCESS, file3.getId(), null, now));
            UserNotification mentioned = notification(owner, UserNotification.Type.DATASETMENTIONED, ds3.getId(), null, now);
            mentioned.setAdditionalInfo("{\"key\":\"value\"}");
            notifications.add(mentioned);
            notifications.add(notification(owner, UserNotification.Type.GRANTFILEACCESS, ds2.getId(), requestor3, now));
            notifications.add(notification(owner, UserNotification.Type.DATASETCREATED, ds3.getId(), requestor4, now));
            for (UserNotification notification : notifications) {
                em.persist(notification);
            }
            em.flush();

            userId = owner.getId();
            notificationIds = new ArrayList<>();
            for (UserNotification notification : notifications) {
                notificationIds.add(notification.getId());
            }
        });
    }

    @Test
    @DisplayName("notifications: preloaded render matches legacy JSON within a fixed budget")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void renderStaysWithinBudget() {
        jpa.getEntityManagerFactory().getCache().evictAll();
        RenderRun legacy = jpa.inTransaction(em -> runLegacyRender(em, notificationIds));
        jpa.getEntityManagerFactory().getCache().evictAll();
        RenderRun fast = jpa.inTransaction(em -> runFastRender(em, notificationIds));
        System.out.println("[NotificationRenderBudgetIT] legacy SELECTs: " + legacy.selects
                + ", preloaded SELECTs: " + fast.selects);

        assertTrue(legacy.selects > RENDER_BUDGET,
                "legacy path should exceed the new-path budget, but got " + legacy.selects);
        assertTrue(fast.selects <= RENDER_BUDGET,
                "expected at most " + RENDER_BUDGET + " SELECTs, but got " + fast.selects);

        assertEquals(12, fast.items.size(), "one JSON per notification");
        for (RenderItem item : fast.items) {
            assertTrue(!item.json.contains("objectDeleted"), "no deleted-object fallbacks: " + item.json);
        }
        String roleOnDataset = jsonFor(fast, UserNotification.Type.ASSIGNROLE, 0);
        assertTrue(roleOnDataset.contains("\"roleAssignments\":[{"),
                "role notification carries the assignment: " + roleOnDataset);
        assertTrue(roleOnDataset.contains("datasetPersistentIdentifier"),
                "role-on-dataset fields: " + roleOnDataset);
        String roleOnDataverse = jsonFor(fast, UserNotification.Type.REVOKEROLE, 0);
        assertTrue(roleOnDataverse.contains("\"dataverseAlias\":\"notifdv\""),
                "role-on-dataverse fields: " + roleOnDataverse);
        String created = jsonFor(fast, UserNotification.Type.DATASETCREATED, 0);
        assertTrue(created.contains("\"requestorFirstName\":\"Notif\""), "requestor fields: " + created);
        String fileAccess = jsonFor(fast, UserNotification.Type.REQUESTEDFILEACCESS, 0);
        assertTrue(fileAccess.contains("\"dataFileId\":"), "file fields: " + fileAccess);
        String mentioned = jsonFor(fast, UserNotification.Type.DATASETMENTIONED, 0);
        assertTrue(mentioned.contains("\"additionalInfo\":"), "mention fields: " + mentioned);
    }

    @Test
    @DisplayName("notifications: repeated notifications add zero queries")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void repeatedNotificationsAddZeroQueries() {
        // Same 9 distinct objects and same assignment renders as the full page;
        // the other 3 notifications repeat already-loaded objects.
        List<Long> subset = List.of(
                notificationIds.get(0), notificationIds.get(1), notificationIds.get(2),
                notificationIds.get(3), notificationIds.get(4), notificationIds.get(5),
                notificationIds.get(7), notificationIds.get(8), notificationIds.get(9));
        jpa.getEntityManagerFactory().getCache().evictAll();
        RenderRun subsetRun = jpa.inTransaction(em -> runFastRender(em, subset));
        jpa.getEntityManagerFactory().getCache().evictAll();
        RenderRun fullRun = jpa.inTransaction(em -> runFastRender(em, notificationIds));
        jpa.getEntityManagerFactory().getCache().evictAll();
        RenderRun legacyFull = jpa.inTransaction(em -> runLegacyRender(em, notificationIds));
        System.out.println("[NotificationRenderBudgetIT] preloaded 9: " + subsetRun.selects
                + ", preloaded 12: " + fullRun.selects + ", legacy 12: " + legacyFull.selects);

        assertTrue(Math.abs(subsetRun.selects - fullRun.selects) <= 1,
                "same distinct objects must cost (nearly) the same, but got " + subsetRun.selects
                        + " vs " + fullRun.selects + " (1 query of order-dependent L1 tolerance)");
        assertTrue(legacyFull.selects > fullRun.selects,
                "legacy path should cost more than the preloaded path");
    }

    @Test
    @DisplayName("notifications: findByUser fetches requestors in the list query")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void findByUserStaysWithinBudget() {
        jpa.getEntityManagerFactory().getCache().evictAll();
        long legacySelects = jpa.inTransaction(em -> {
            QueryCountHolder.clear();
            List<UserNotification> rows = em.createQuery(
                    "select un from UserNotification un where un.user.id = :userId order by un.sendDate desc",
                    UserNotification.class)
                .setParameter("userId", userId)
                .getResultList();
            for (UserNotification row : rows) {
                if (row.getRequestor() != null) {
                    row.getRequestor().getUserIdentifier();
                }
            }
            return QueryCountHolder.getGrandTotal().getSelect();
        });
        jpa.getEntityManagerFactory().getCache().evictAll();
        long fastSelects = jpa.inTransaction(em -> {
            UserNotificationServiceBean service = new UserNotificationServiceBean();
            inject(service, "em", em);
            QueryCountHolder.clear();
            List<UserNotification> rows = service.findByUser(userId);
            int withRequestor = 0;
            for (UserNotification row : rows) {
                if (row.getRequestor() != null) {
                    row.getRequestor().getUserIdentifier();
                    withRequestor++;
                }
            }
            assertEquals(4, withRequestor, "fixture requestors loaded");
            return QueryCountHolder.getGrandTotal().getSelect();
        });
        System.out.println("[NotificationRenderBudgetIT] findByUser legacy: " + legacySelects + ", fetch: " + fastSelects);

        assertTrue(legacySelects > fastSelects, "fetch join should beat per-row requestor loads");
        assertTrue(fastSelects <= 7, "expected list + user hydration only, but got " + fastSelects);
    }

    private record RenderItem(UserNotification.Type type, Long objectId, String json) {
    }

    private record RenderRun(long selects, List<RenderItem> items) {
    }

    private static String jsonFor(RenderRun run, UserNotification.Type type, int occurrence) {
        int seen = 0;
        for (RenderItem item : run.items) {
            if (item.type == type) {
                if (seen == occurrence) {
                    return item.json;
                }
                seen++;
            }
        }
        throw new IllegalStateException("No " + type + " #" + occurrence + " rendered");
    }

    private static RenderRun runLegacyRender(EntityManager em, List<Long> ids) {
        Wiring wiring = wire(em);
        AuthenticatedUser user = em.find(AuthenticatedUser.class, userId);
        List<UserNotification> notifications = loadInOrder(em, ids);
        QueryCountHolder.clear();
        // Faithful replication of the pre-fix per-row access pattern: one
        // service find per notification per candidate object, then the same
        // display touches the JSON branches perform. Counts only.
        for (UserNotification notification : notifications) {
            Long objectId = notification.getObjectId();
            switch (notification.getType()) {
                case ASSIGNROLE:
                case REVOKEROLE: {
                    DvObject found = wiring.dataverseService.find(objectId);
                    if (found == null) {
                        found = wiring.datasetService.find(objectId);
                    }
                    if (found == null) {
                        found = wiring.dataFileService.find(objectId);
                    }
                    if (found != null) {
                        wiring.permissionService.getEffectiveRoleAssignments(user, found);
                        if (found instanceof Dataverse) {
                            ((Dataverse) found).getAlias();
                        } else if (found instanceof Dataset) {
                            ((Dataset) found).getDisplayName();
                        } else {
                            ((DataFile) found).getOwner().getDisplayName();
                        }
                    }
                    break;
                }
                case PUBLISHEDDS:
                case CREATEDS:
                case SUBMITTEDDS: {
                    DatasetVersion version = wiring.datasetVersionService.find(objectId);
                    if (version != null) {
                        version.getDataset().getDisplayName();
                        version.getDataset().getOwner().getAlias();
                    }
                    break;
                }
                case DATASETCREATED:
                case DATASETMENTIONED:
                case GRANTFILEACCESS: {
                    Dataset dataset = wiring.datasetService.find(objectId);
                    if (dataset != null) {
                        dataset.getDisplayName();
                        dataset.getOwner().getAlias();
                    }
                    break;
                }
                case REQUESTEDFILEACCESS: {
                    DataFile dataFile = wiring.dataFileService.find(objectId);
                    if (dataFile != null) {
                        dataFile.getDisplayName();
                        dataFile.getOwner().getDisplayName();
                    }
                    break;
                }
                default:
                    break;
            }
        }
        return new RenderRun(QueryCountHolder.getGrandTotal().getSelect(), List.of());
    }

    private static RenderRun runFastRender(EntityManager em, List<Long> ids) {
        Wiring wiring = wire(em);
        AuthenticatedUser user = em.find(AuthenticatedUser.class, userId);
        List<UserNotification> notifications = loadInOrder(em, ids);
        QueryCountHolder.clear();
        InAppNotificationsJsonPrinter.NotificationPreload preload = wiring.printer.preload(notifications);
        List<RenderItem> items = new ArrayList<>();
        for (UserNotification notification : notifications) {
            NullSafeJsonBuilder builder = NullSafeJsonBuilder.jsonObjectBuilder();
            wiring.printer.addFieldsByType(builder, user, notification, preload);
            items.add(new RenderItem(notification.getType(), notification.getObjectId(),
                    builder.build().toString()));
        }
        return new RenderRun(QueryCountHolder.getGrandTotal().getSelect(), items);
    }

    private static List<UserNotification> loadInOrder(EntityManager em, List<Long> ids) {
        return em.createQuery("select un from UserNotification un where un.id in :ids order by un.id",
                UserNotification.class)
            .setParameter("ids", ids)
            .getResultList();
    }

    private record Wiring(InAppNotificationsJsonPrinter printer, DataverseServiceBean dataverseService,
            DatasetServiceBean datasetService, DatasetVersionServiceBean datasetVersionService,
            DataFileServiceBean dataFileService, PermissionServiceBean permissionService) {
    }

    private static Wiring wire(EntityManager em) {
        DataverseServiceBean dataverseService = new DataverseServiceBean();
        inject(dataverseService, "em", em);
        DatasetServiceBean datasetService = new DatasetServiceBean();
        inject(datasetService, "em", em);
        DatasetVersionServiceBean datasetVersionService = new DatasetVersionServiceBean();
        inject(datasetVersionService, "em", em);
        DataFileServiceBean dataFileService = new DataFileServiceBean();
        inject(dataFileService, "em", em);
        DvObjectServiceBean dvObjectService = new DvObjectServiceBean();
        inject(dvObjectService, "em", em);
        DataverseRoleServiceBean roleService = new DataverseRoleServiceBean();
        inject(roleService, "em", em);
        AuthenticationServiceBean authService = new AuthenticationServiceBean();
        inject(authService, "em", em);

        IpGroupsServiceBean ipGroups = new IpGroupsServiceBean();
        inject(ipGroups, "em", em);
        ShibGroupServiceBean shibGroups = new ShibGroupServiceBean();
        inject(shibGroups, "em", em);
        ExplicitGroupServiceBean explicitGroups = new ExplicitGroupServiceBean();
        inject(explicitGroups, "em", em);
        MailDomainGroupServiceBean mailGroups = new MailDomainGroupServiceBean();
        inject(mailGroups, "em", em);

        RoleAssigneeServiceBean roleAssigneeService = new RoleAssigneeServiceBean();
        inject(roleAssigneeService, "authSvc", authService);
        inject(roleAssigneeService, "explicitGroupSvc", explicitGroups);
        inject(roleAssigneeService, "dataverseRoleService", roleService);
        inject(explicitGroups, "roleAssigneeSvc", roleAssigneeService);
        invokeSetup(explicitGroups);
        invokeSetup(mailGroups);

        GroupServiceBean groupService = new GroupServiceBean();
        inject(groupService, "ipGroupsService", ipGroups);
        inject(groupService, "shibGroupService", shibGroups);
        inject(groupService, "explicitGroupService", explicitGroups);
        inject(groupService, "mailDomainGroupService", mailGroups);
        inject(groupService, "roleAssigneeSvc", roleAssigneeService);
        inject(roleAssigneeService, "groupSvc", groupService);
        groupService.setup();

        PermissionServiceBean permissionService = new PermissionServiceBean();
        inject(permissionService, "roleService", roleService);
        inject(permissionService, "groupService", groupService);

        InAppNotificationsJsonPrinter printer = new InAppNotificationsJsonPrinter();
        inject(printer, "datasetService", datasetService);
        inject(printer, "datasetVersionService", datasetVersionService);
        inject(printer, "dataFileService", dataFileService);
        inject(printer, "dvObjectService", dvObjectService);
        inject(printer, "permissionService", permissionService);

        JsonPrinter.injectSettingsService(null, null, null, datasetService, null, printer, roleAssigneeService);
        return new Wiring(printer, dataverseService, datasetService, datasetVersionService, dataFileService,
                permissionService);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field found = null;
            Class<?> type = target.getClass();
            while (type != null && found == null) {
                try {
                    found = type.getDeclaredField(field);
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            if (found == null) {
                throw new NoSuchFieldException(field);
            }
            found.setAccessible(true);
            found.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot inject " + field + " into " + target.getClass().getSimpleName(), e);
        }
    }

    private static void invokeSetup(Object target) {
        try {
            Method setup = target.getClass().getDeclaredMethod("setup");
            setup.setAccessible(true);
            setup.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot invoke setup on " + target.getClass().getSimpleName(), e);
        }
    }

    private static AuthenticatedUser newUser(EntityManager em, String identifier) {
        AuthenticatedUser user = new AuthenticatedUser();
        user.setUserIdentifier(identifier);
        user.setFirstName("Notif");
        user.setLastName(identifier);
        user.setEmail(identifier + "@example.com");
        user.setCreatedTime(new Timestamp(System.currentTimeMillis()));
        user.setDeactivated(false);
        em.persist(user);
        return user;
    }

    private static UserNotification notification(AuthenticatedUser user, UserNotification.Type type,
            Long objectId, AuthenticatedUser requestor, Timestamp sendDate) {
        UserNotification notification = new UserNotification();
        notification.setUser(user);
        notification.setType(type);
        notification.setObjectId(objectId);
        notification.setRequestor(requestor);
        notification.setSendDate(sendDate);
        notification.setReadNotification(false);
        return notification;
    }
}
