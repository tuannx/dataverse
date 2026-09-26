package edu.harvard.iq.dataverse.datavariable;

import edu.harvard.iq.dataverse.DataFile;
import edu.harvard.iq.dataverse.Dataset;
import edu.harvard.iq.dataverse.DatasetVersion;
import edu.harvard.iq.dataverse.FileMetadata;
import edu.harvard.iq.dataverse.util.testing.fixtures.DatasetFixtureBuilder;
import edu.harvard.iq.dataverse.util.testing.performance.JpaEntityManagerService;
import edu.harvard.iq.dataverse.util.testing.performance.JpaPerformanceTest;
import edu.harvard.iq.dataverse.util.testing.recipes.DatasetRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.DatasetTypeRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.FileRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.VariableMetadataRecipe;
import edu.harvard.iq.dataverse.util.testing.recipes.VariableSetRecipe;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for the Dataset page Versions tab N+1: variable metadata is
 * loaded with 2 service queries <b>per file of every version</b>.
 *
 * <p>Mirrors the privileged branch of {@code DatasetPage.resetVersionTabList()}:
 * for each {@link FileMetadata} it calls
 * {@code variableService.findVarMetByFileMetaId(fm.getId())} and
 * {@code variableService.findAllGroupsByFileMetadata(fm.getId())}.
 * With {@value #FILE_COUNT} files x {@value #VERSION_COUNT} versions the
 * service-call section alone issues 2 queries per file pre-fix (24 service
 * SELECTs for this fixture, plus per-row eager hydration of the variable
 * graph) and exactly 2 SELECTs after the fix, regardless of file count.
 *
 * <p>Contract after the fix: collect all file-metadata ids across versions,
 * load {@link VariableMetadata} and {@link VarGroup} with 2 batched
 * {@code IN (...)} queries, group in memory. The service-call section costs
 * a constant 2 SELECTs.
 *
 * <p>Measurement is scoped with {@link QueryCountHolder} to the service calls
 * only: hydrating {@code FileMetadata.dataFile} per row is a separate,
 * pre-existing N+1 ({@code eclipselink.weaving=false}, see upstream #12740),
 * so the global same-select rule is cancelled for this test.
 */
@JpaPerformanceTest
class VersionTabVariableLoadBudgetIT {

    static JpaEntityManagerService jpa;
    static Long datasetId;

    /** Files per version; 2 service queries per file pre-fix. */
    static final int FILE_COUNT = 6;
    /** Versions sharing the same data files (like a real draft on top of a release). */
    static final int VERSION_COUNT = 2;
    /** Variables (and variable-metadata rows) per tabular file. */
    static final int VARS_PER_FILE = 3;

    @BeforeAll
    static void setUp() {
        jpa.start();

        DatasetRecipe sampleRecipe = DatasetRecipe.of(
            DatasetTypeRecipe.dataset(),
            VersionRecipe.of(
                FileRecipe.tabular(FILE_COUNT, VariableSetRecipe.uniform(VARS_PER_FILE, VariableMetadataRecipe.always()))
            )
        );

        var fixture = DatasetFixtureBuilder.builder().recipe(sampleRecipe).build();

        jpa.inTransactionVoid(em -> em.persist(fixture.datasetType()));

        Dataset sampleDataset = fixture.dataset();
        jpa.inTransactionVoid(em -> {
            for (DataFile dataFile : fixture.dataFiles()) {
                em.persist(dataFile);
            }
            em.persist(sampleDataset);
        });

        // Second version reusing the same data files with fresh FileMetadata rows,
        // mirroring what a real new draft version looks like.
        jpa.inTransactionVoid(em -> {
            Dataset managed = em.find(Dataset.class, sampleDataset.getId());
            DatasetVersion v2 = new DatasetVersion();
            v2.setDataset(managed);
            v2.setVersionState(DatasetVersion.VersionState.DRAFT);
            v2.setVersionNumber(2L);
            v2.setMinorVersionNumber(0L);
            v2.setCreateTime(new Date());
            v2.setLastUpdateTime(new Date());
            List<FileMetadata> copies = new ArrayList<>();
            for (FileMetadata fm : managed.getVersions().get(0).getFileMetadatas()) {
                FileMetadata copy = new FileMetadata();
                copy.setLabel(fm.getLabel());
                copy.setDataFile(fm.getDataFile());
                copy.setDatasetVersion(v2);
                copies.add(copy);
            }
            v2.setFileMetadatas(copies);
            em.persist(v2);
        });

        datasetId = sampleDataset.getId();
    }

    @Test
    @DisplayName("Versions tab: variable-service calls for all files cost 2 SELECTs, not 2 per file")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void versionsTabVariableLoadStaysWithinBudget() {
        // One persistence context for the whole read, mirroring a page render:
        // versions/files load first (unmeasured), then the measured section runs
        // the exact service-call pattern of resetVersionTabList().
        long[] serviceSelects = {0};
        int[] totalFiles = {0};
        Map<Long, Integer> varMetCounts = jpa.inTransaction(em -> {
            VariableServiceBean variableService = newVariableService(em);
            Dataset dataset = em.find(Dataset.class, datasetId);
            List<Long> allFmIds = new ArrayList<>();
            for (DatasetVersion version : dataset.getVersions()) {
                for (FileMetadata fm : version.getFileMetadatas()) {
                    allFmIds.add(fm.getId());
                }
            }
            totalFiles[0] = allFmIds.size();

            QueryCountHolder.clear();
            Map<Long, Integer> counts = new HashMap<>();
            for (VariableMetadata vm : variableService.findVarMetsByFileMetaIds(allFmIds)) {
                counts.merge(vm.getFileMetadata().getId(), 1, Integer::sum);
            }
            variableService.findAllGroupsByFileMetadatas(allFmIds);
            serviceSelects[0] = QueryCountHolder.getGrandTotal().getSelect();
            return counts;
        });
        System.out.println("[VersionTabVariableLoadBudgetIT] service-call SELECTs: " + serviceSelects[0]);

        assertEquals(FILE_COUNT * VERSION_COUNT, totalFiles[0], "seeded files x versions");
        assertTrue(serviceSelects[0] <= 2,
                "expected 2 batched variable-service SELECTs, but got " + serviceSelects[0]);

        // Behavioral check: only v1 files carry variable metadata (3 rows each).
        int totalVarMets = varMetCounts.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(FILE_COUNT * VARS_PER_FILE, totalVarMets, "v1 variable-metadata rows loaded");
    }

    private static VariableServiceBean newVariableService(EntityManager em) {
        VariableServiceBean bean = new VariableServiceBean();
        try {
            Field emField = VariableServiceBean.class.getDeclaredField("em");
            emField.setAccessible(true);
            emField.set(bean, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inject EntityManager into VariableServiceBean", e);
        }
        return bean;
    }
}
