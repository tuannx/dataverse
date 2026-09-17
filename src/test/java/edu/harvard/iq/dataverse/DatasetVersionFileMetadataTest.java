package edu.harvard.iq.dataverse;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class DatasetVersionFileMetadataTest {

    @Test
    public void testGetFileMetadatasSorted_FiltersDeleted() {
        DatasetVersion datasetVersion = new DatasetVersion();

        List<FileMetadata> metadataList = new ArrayList<>();

        FileMetadata fm1 = new FileMetadata();
        DataFile df1 = new DataFile();
        fm1.setDataFile(df1);
        fm1.setLabel("file1.txt");
        metadataList.add(fm1);

        FileMetadata fm2 = new FileMetadata();
        DataFile df2 = new DataFile();
        df2.setDeleted(true); // MARKED DELETED
        fm2.setDataFile(df2);
        fm2.setLabel("file2.txt");
        metadataList.add(fm2);

        FileMetadata fm3 = new FileMetadata();
        DataFile df3 = new DataFile();
        fm3.setDataFile(df3);
        fm3.setLabel("file3.txt");
        metadataList.add(fm3);

        datasetVersion.setFileMetadatas(metadataList);

        List<FileMetadata> sortedList = datasetVersion.getFileMetadatasSorted();

        assertEquals(2, sortedList.size(), "Deleted files should not be included in getFileMetadatasSorted");
    }
}
