package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.authorization.AuthenticationServiceBean;
import edu.harvard.iq.dataverse.authorization.DataverseRole;
import edu.harvard.iq.dataverse.authorization.RoleAssignee;
import edu.harvard.iq.dataverse.authorization.groups.GroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.explicit.ExplicitGroup;
import edu.harvard.iq.dataverse.authorization.groups.impl.explicit.ExplicitGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.ipaddress.IpGroupsServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.maildomain.MailDomainGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.groups.impl.shib.ShibGroupServiceBean;
import edu.harvard.iq.dataverse.authorization.users.AuthenticatedUser;
import edu.harvard.iq.dataverse.dataset.DatasetType;
import edu.harvard.iq.dataverse.util.testing.performance.JpaEntityManagerService;
import edu.harvard.iq.dataverse.util.testing.performance.JpaPerformanceTest;
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
import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for the Manage Permissions page N+1: each role-assignment row
 * resolves its assignee with an individual lookup
 * ({@code RoleAssigneeServiceBean.getRoleAssignee}), costing one query per
 * user and one per group.
 *
 * <p>The fixture assigns 6 users, 2 explicit groups and 1 builtin group on a
 * dataset. The test asserts the per-row pattern exceeds the new-path budget
 * (RED proof), the batched resolution stays within it, and both resolve every
 * identifier to the same assignee.
 */
@JpaPerformanceTest
class RoleAssigneeBatchBudgetIT {

    static JpaEntityManagerService jpa;
    static List<String> assigneeIdentifiers;

    /** Fixed budget for resolving the 9-identifier fixture page. Calibrated, see IT output. */
    static final int RESOLVE_BUDGET = 6;

    @BeforeAll
    static void setUp() {
        jpa.start();

        jpa.inTransactionVoid(em -> {
            Dataverse dataverse = new Dataverse();
            dataverse.setAlias("permdv");
            dataverse.setName("Perm Dataverse");
            dataverse.setDataverseType(Dataverse.DataverseType.UNCATEGORIZED);
            dataverse.getDataverseContacts().add(new DataverseContact(dataverse, "perm@example.com"));
            dataverse.setCreateDate(new Timestamp(System.currentTimeMillis()));
            dataverse.setModificationTime(new Timestamp(System.currentTimeMillis()));
            em.persist(dataverse);
            em.flush();

            DatasetType datasetType = new DatasetType();
            datasetType.setName("permtype");
            em.persist(datasetType);

            Dataset dataset = new Dataset();
            dataset.setDatasetType(datasetType);
            dataset.getVersions().get(0).setCreateTime(new Date());
            dataset.getVersions().get(0).setLastUpdateTime(new Date());
            dataset.setOwner(dataverse);
            dataset.setCreateDate(new Timestamp(System.currentTimeMillis()));
            dataset.setModificationTime(new Timestamp(System.currentTimeMillis()));
            dataset.setProtocol("doi");
            dataset.setAuthority("10.7910/perm");
            dataset.setIdentifier("PERM1");
            em.persist(dataset);

            DataverseRole role = new DataverseRole();
            role.setName("PermRole");
            role.setAlias("permrole");
            role.setDescription("permissions fixture role");
            em.persist(role);

            List<String> identifiers = new ArrayList<>();
            for (int i = 1; i <= 6; i++) {
                AuthenticatedUser user = newUser(em, "permuser" + i);
                assign(em, "@" + user.getUserIdentifier(), role, dataset);
                identifiers.add("@" + user.getUserIdentifier());
            }
            for (int i = 1; i <= 2; i++) {
                // Provider is transient (runtime only); null is fine for persistence.
                ExplicitGroup group = new ExplicitGroup(null);
                group.setOwner(dataverse);
                group.setGroupAliasInOwner("permgroup" + i);
                group.setDisplayName("Perm Group " + i);
                group.setDescription("permissions fixture group");
                em.persist(group);
                em.flush();
                String identifier = "&explicit/" + group.getAlias();
                assign(em, identifier, role, dataset);
                identifiers.add(identifier);
            }
            assign(em, ":authenticated-users", role, dataset);
            identifiers.add(":authenticated-users");
            em.flush();

            assigneeIdentifiers = identifiers;
        });
    }

    @Test
    @DisplayName("permissions: batched assignee resolution stays within budget, not 1 per row")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void assigneeResolutionStaysWithinBudget() {
        jpa.getEntityManagerFactory().getCache().evictAll();
        ResolveRun legacy = jpa.inTransaction(em -> {
            RoleAssigneeServiceBean service = wire(em);
            QueryCountHolder.clear();
            Map<String, String> resolved = new HashMap<>();
            for (String identifier : assigneeIdentifiers) {
                RoleAssignee assignee = service.getRoleAssignee(identifier);
                if (assignee != null) {
                    resolved.put(identifier, assignee.getDisplayInfo().getTitle());
                }
            }
            return new ResolveRun(QueryCountHolder.getGrandTotal().getSelect(), resolved);
        });
        jpa.getEntityManagerFactory().getCache().evictAll();
        ResolveRun fast = jpa.inTransaction(em -> {
            RoleAssigneeServiceBean service = wire(em);
            QueryCountHolder.clear();
            Map<String, RoleAssignee> assignees = service.getRoleAssignees(assigneeIdentifiers);
            Map<String, String> resolved = new HashMap<>();
            for (Map.Entry<String, RoleAssignee> entry : assignees.entrySet()) {
                resolved.put(entry.getKey(), entry.getValue().getDisplayInfo().getTitle());
            }
            return new ResolveRun(QueryCountHolder.getGrandTotal().getSelect(), resolved);
        });
        System.out.println("[RoleAssigneeBatchBudgetIT] legacy SELECTs: " + legacy.selects
                + ", batched SELECTs: " + fast.selects);

        assertTrue(legacy.selects > RESOLVE_BUDGET,
                "legacy path should exceed the new-path budget, but got " + legacy.selects);
        assertTrue(fast.selects <= RESOLVE_BUDGET,
                "expected at most " + RESOLVE_BUDGET + " SELECTs, but got " + fast.selects);
        assertEquals(legacy.resolved, fast.resolved, "same assignees resolved");
        assertEquals(9, fast.resolved.size(), "every identifier resolved");
    }

    private record ResolveRun(long selects, Map<String, String> resolved) {
    }

    private static RoleAssigneeServiceBean wire(EntityManager em) {
        AuthenticationServiceBean authService = new AuthenticationServiceBean();
        inject(authService, "em", em);
        DataverseRoleServiceBean roleService = new DataverseRoleServiceBean();
        inject(roleService, "em", em);

        IpGroupsServiceBean ipGroups = new IpGroupsServiceBean();
        inject(ipGroups, "em", em);
        ShibGroupServiceBean shibGroups = new ShibGroupServiceBean();
        inject(shibGroups, "em", em);
        ExplicitGroupServiceBean explicitGroups = new ExplicitGroupServiceBean();
        inject(explicitGroups, "em", em);
        MailDomainGroupServiceBean mailGroups = new MailDomainGroupServiceBean();
        inject(mailGroups, "em", em);

        RoleAssigneeServiceBean assigneeService = new RoleAssigneeServiceBean();
        inject(assigneeService, "authSvc", authService);
        inject(assigneeService, "explicitGroupSvc", explicitGroups);
        inject(assigneeService, "dataverseRoleService", roleService);
        inject(explicitGroups, "roleAssigneeSvc", assigneeService);
        invokeSetup(explicitGroups);
        invokeSetup(mailGroups);

        GroupServiceBean groupService = new GroupServiceBean();
        inject(groupService, "ipGroupsService", ipGroups);
        inject(groupService, "shibGroupService", shibGroups);
        inject(groupService, "explicitGroupService", explicitGroups);
        inject(groupService, "mailDomainGroupService", mailGroups);
        inject(groupService, "roleAssigneeSvc", assigneeService);
        inject(assigneeService, "groupSvc", groupService);
        groupService.setup();

        invokeSetup(assigneeService);
        return assigneeService;
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
            Method setup = null;
            Class<?> type = target.getClass();
            while (type != null && setup == null) {
                try {
                    setup = type.getDeclaredMethod("setup");
                } catch (NoSuchMethodException e) {
                    type = type.getSuperclass();
                }
            }
            if (setup == null) {
                throw new NoSuchMethodException("setup");
            }
            setup.setAccessible(true);
            setup.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot invoke setup on " + target.getClass().getSimpleName(), e);
        }
    }

    private static AuthenticatedUser newUser(EntityManager em, String identifier) {
        AuthenticatedUser user = new AuthenticatedUser();
        user.setUserIdentifier(identifier);
        user.setFirstName("Perm");
        user.setLastName(identifier);
        user.setEmail(identifier + "@example.com");
        user.setCreatedTime(new Timestamp(System.currentTimeMillis()));
        user.setDeactivated(false);
        em.persist(user);
        return user;
    }

    private static void assign(EntityManager em, String assigneeIdentifier, DataverseRole role, Dataset dataset) {
        RoleAssignment assignment = new RoleAssignment();
        assignment.setAssigneeIdentifier(assigneeIdentifier);
        assignment.setRole(role);
        assignment.setDefinitionPoint(dataset);
        em.persist(assignment);
    }
}
