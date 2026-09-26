package edu.harvard.iq.dataverse;

import edu.harvard.iq.dataverse.datavariable.VariableMetadata;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for upstream #12102: {@code /versions/compareSummary} takes
 * ~17s on a dataset with 1.5K files because
 * {@code DatasetVersion.getDefaultVersionDifference()} hydrates and crawls
 * the full file graphs of both versions.
 *
 * <p>Each fixture dataset has two versions; v2 differs from v1 by exactly 4
 * files (1 added, 1 removed, 1 renamed, 1 with edited variable notes) while
 * the remaining files are byte-identical copies. The test asserts:
 * <ul>
 *   <li>the legacy path exceeds the new-path budget (RED proof),</li>
 *   <li>the native-query path ({@code DatasetVersionServiceBean})
 *       stays within a fixed budget,</li>
 *   <li>both paths produce identical diffs, down to the summary JSON, and</li>
 *   <li>the native-query path costs the same for 12 and 24 files: it scales
 *       with the size of the change, not the dataset.</li>
 * </ul>
 */
@JpaPerformanceTest
class CompareSummaryBudgetIT {

    static JpaEntityManagerService jpa;
    static Long smallDatasetId;
    static Long largeDatasetId;

    static final int TABULAR_FILES = 4;
    static final int VARS_PER_FILE = 3;
    /**
     * Fixed budget for the native-query diff of one version pair. Measured 32
     * (1 native + 3 batched loads + version hydration + pre-existing EAGER
     * residuals per <i>involved</i> file); headroom to 36. The scaling test
     * below pins the key property exactly: unchanged files add zero queries.
     */
    static final int NEW_PATH_BUDGET = 36;

    @BeforeAll
    static void setUp() {
        jpa.start();
        smallDatasetId = seedDataset(8, null);
        Long sharedTypeId = jpa.inTransaction(em ->
            em.find(Dataset.class, smallDatasetId).getDatasetType().getId());
        largeDatasetId = seedDataset(20, sharedTypeId);
    }

    @Test
    @DisplayName("compareSummary: native-query diff matches legacy output within a fixed budget")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void compareSummaryStaysWithinBudget() {
        DiffRun legacy = jpa.inTransaction(em -> runLegacyDiff(em, smallDatasetId));
        DiffRun fast = jpa.inTransaction(em -> runFastDiff(em, smallDatasetId));
        System.out.println("[CompareSummaryBudgetIT] legacy SELECTs: " + legacy.selects
                + ", native-query SELECTs: " + fast.selects);

        assertTrue(legacy.selects > NEW_PATH_BUDGET,
                "legacy path should exceed the new-path budget, but got " + legacy.selects);
        assertTrue(fast.selects <= NEW_PATH_BUDGET,
                "expected at most " + NEW_PATH_BUDGET + " SELECTs, but got " + fast.selects);

        assertEquals(legacy.added, fast.added, "added files");
        assertEquals(legacy.removed, fast.removed, "removed files");
        assertEquals(legacy.changedMeta, fast.changedMeta, "changed file metadata");
        assertEquals(legacy.changedVarMeta, fast.changedVarMeta, "changed variable metadata");
        assertEquals(legacy.diffListSize, fast.diffListSize, "detailed file diff items");
        assertEquals(legacy.summaryJson, fast.summaryJson, "compareSummary JSON");
    }

    @Test
    @DisplayName("compareSummary: native-query cost does not grow with unchanged files")
    @EnableSameSelectTypesWithDifferentParamValues
    @ExpectUpdate(0)
    @ExpectInsert(0)
    @ExpectDelete(0)
    void compareSummaryCostIsIndependentOfUnchangedFiles() {
        DiffRun smallFast = jpa.inTransaction(em -> runFastDiff(em, smallDatasetId));
        DiffRun largeFast = jpa.inTransaction(em -> runFastDiff(em, largeDatasetId));
        DiffRun largeLegacy = jpa.inTransaction(em -> runLegacyDiff(em, largeDatasetId));
        System.out.println("[CompareSummaryBudgetIT] native 12-file: " + smallFast.selects
                + ", native 24-file: " + largeFast.selects + ", legacy 24-file: " + largeLegacy.selects);

        assertEquals(smallFast.selects, largeFast.selects,
                "unchanged files must add zero queries");
        assertTrue(largeLegacy.selects > smallFast.selects,
                "legacy path should scale with file count");
    }

    private static DiffRun runLegacyDiff(EntityManager em, Long datasetId) {
        Dataset dataset = em.find(Dataset.class, datasetId);
        DatasetVersion v2 = dataset.getVersions().get(0);
        QueryCountHolder.clear();
        DatasetVersionDifference diff = v2.getDefaultVersionDifference();
        return DiffRun.capture(diff);
    }

    private static DiffRun runFastDiff(EntityManager em, Long datasetId) {
        DatasetVersionServiceBean service = newVersionService(em);
        Dataset dataset = em.find(Dataset.class, datasetId);
        DatasetVersion v2 = dataset.getVersions().get(0);
        QueryCountHolder.clear();
        DatasetVersionDifference diff = service.buildDefaultVersionDifference(v2);
        return DiffRun.capture(diff);
    }

    private static DatasetVersionServiceBean newVersionService(EntityManager em) {
        DatasetVersionServiceBean bean = new DatasetVersionServiceBean();
        try {
            Field emField = DatasetVersionServiceBean.class.getDeclaredField("em");
            emField.setAccessible(true);
            emField.set(bean, em);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot inject EntityManager into DatasetVersionServiceBean", e);
        }
        return bean;
    }

    private record DiffRun(long selects, Set<Long> added, Set<Long> removed,
            Set<Long> changedMeta, Set<Long> changedVarMeta, int diffListSize, String summaryJson) {
        static DiffRun capture(DatasetVersionDifference diff) {
            long selects = QueryCountHolder.getGrandTotal().getSelect();
            return new DiffRun(selects,
                    datafileIds(diff.getAddedFiles()),
                    datafileIds(diff.getRemovedFiles()),
                    fileMetadataIds(diff.getChangedFileMetadata()),
                    fileMetadataIds(diff.getChangedVariableMetadata()),
                    diff.getDatasetFilesDiffList().size(),
                    diff.getSummaryDifferenceAsJson().build().toString());
        }

        private static Set<Long> datafileIds(List<FileMetadata> files) {
            Set<Long> ids = new HashSet<>();
            for (FileMetadata fm : files) {
                ids.add(fm.getDataFile().getId());
            }
            return ids;
        }

        private static Set<Long> fileMetadataIds(List<FileMetadata> files) {
            Set<Long> ids = new HashSet<>();
            for (FileMetadata fm : files) {
                ids.add(fm.getId());
            }
            return ids;
        }
    }

    /**
     * Seeds one dataset with v1 ({@code regularFiles} regular + 4 tabular files)
     * and a v2 draft that renames 1 file, removes 1, adds 1 and edits variable
     * notes on 1 tabular file. Returns the dataset id.
     */
    private static Long seedDataset(int regularFiles, Long sharedDatasetTypeId) {
        DatasetRecipe recipe = DatasetRecipe.of(
            DatasetTypeRecipe.dataset(),
            VersionRecipe.of(
                FileRecipe.regular(regularFiles),
                FileRecipe.tabular(TABULAR_FILES, VariableSetRecipe.uniform(VARS_PER_FILE, VariableMetadataRecipe.always()))
            )
        );
        var fixture = DatasetFixtureBuilder.builder().recipe(recipe).build();

        Dataset dataset = fixture.dataset();
        jpa.inTransactionVoid(em -> {
            if (sharedDatasetTypeId == null) {
                em.persist(fixture.datasetType());
            } else {
                dataset.setDatasetType(em.getReference(
                    edu.harvard.iq.dataverse.dataset.DatasetType.class, sharedDatasetTypeId));
            }
            for (DataFile dataFile : fixture.dataFiles()) {
                em.persist(dataFile);
            }
            em.persist(dataset);
        });

        jpa.inTransactionVoid(em -> {
            Dataset managed = em.find(Dataset.class, dataset.getId());
            DatasetVersion v1 = managed.getVersions().get(0);
            v1.setVersionState(DatasetVersion.VersionState.RELEASED);
            v1.setVersionNumber(1L);
            v1.setMinorVersionNumber(0L);

            List<FileMetadata> regulars = new ArrayList<>();
            List<FileMetadata> tabulars = new ArrayList<>();
            for (FileMetadata fm : v1.getFileMetadatas()) {
                if (fm.getDataFile().getDataTables() == null || fm.getDataFile().getDataTables().isEmpty()) {
                    regulars.add(fm);
                } else {
                    tabulars.add(fm);
                }
            }

            DatasetVersion v2 = new DatasetVersion();
            v2.setDataset(managed);
            v2.setVersionState(DatasetVersion.VersionState.DRAFT);
            v2.setVersionNumber(2L);
            v2.setMinorVersionNumber(0L);
            v2.setCreateTime(new Date());
            v2.setLastUpdateTime(new Date());
            v2.setFileMetadatas(new ArrayList<>());

            // 1 renamed + the rest copied verbatim; last regular file removed.
            for (int i = 0; i < regulars.size() - 1; i++) {
                FileMetadata copy = regulars.get(i).createCopyInVersion(v2);
                if (i == 0) {
                    copy.setLabel("renamed-" + copy.getLabel());
                }
            }
            // Tabular copies with variable rows; first one gets edited notes.
            for (int i = 0; i < tabulars.size(); i++) {
                FileMetadata original = tabulars.get(i);
                FileMetadata copy = original.createCopyInVersion(v2);
                copy.copyVariableMetadata(original.getVariableMetadatas());
                copy.copyVarGroups(original.getVarGroups());
                if (i == 0) {
                    copy.getVariableMetadatas().iterator().next().setNotes("edited-in-v2");
                }
            }
            // 1 brand-new file.
            DataFile added = new DataFile();
            added.setContentType("application/pdf");
            added.setChecksumType(DataFile.ChecksumType.SHA1);
            added.setChecksumValue("fixture-added-checksum");
            added.setFilesize(4096L);
            added.setCreateDate(new java.sql.Timestamp(System.currentTimeMillis()));
            added.setModificationTime(new java.sql.Timestamp(System.currentTimeMillis()));
            em.persist(added);
            FileMetadata addedFm = new FileMetadata();
            addedFm.setLabel("added-file.pdf");
            addedFm.setDataFile(added);
            addedFm.setDatasetVersion(v2);
            v2.getFileMetadatas().add(addedFm);

            em.persist(v2);
        });

        return dataset.getId();
    }
}
