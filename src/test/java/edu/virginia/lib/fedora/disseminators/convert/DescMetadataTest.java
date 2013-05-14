package edu.virginia.lib.fedora.disseminators.convert;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import junit.framework.Assert;

import org.junit.Test;

public class DescMetadataTest {

    /**
     * Tests the ability to parse out whether a record is complete.  This is
     * implied by the absence of a mods:identifier with the attribute invalid
     * equaling 'true'.
     */
    @Test
    public void testCompleteRecordParsing() throws Exception {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        DescMetadata m = (DescMetadata) u.unmarshal(getClass().getClassLoader().getResourceAsStream("image-mods.xml"));
        Assert.assertEquals("Example file is complete.", true, m.isFullRecord());
    }

    /**
     * Tests the ability to parse out whether a record is complete.  This is
     * implied by the presence of a mods:identifier with the attribute invalid
     * equaling 'true'.
     */
    @Test
    public void testIncompleteRecordtParsing() throws Exception {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        DescMetadata m = (DescMetadata) u.unmarshal(getClass().getClassLoader().getResourceAsStream("page-mods.xml"));
        Assert.assertEquals("Example file is complete.", false, m.isFullRecord());
    }

    @Test 
    public void testTitle() throws Exception {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        DescMetadata m = (DescMetadata) u.unmarshal(getClass().getClassLoader().getResourceAsStream("image-mods.xml"));
        String expectedTitle = "Monticello ";
        Assert.assertEquals("Title should be \"" + expectedTitle + "\".", expectedTitle, m.getFirstTitle());
    }

    /**
     * Sometimes a citation has to be built by interpreting the code "SPEC-COLL"
     * from within a 
     * mods:location/mods:holidngSimple/mods:copyInformation/mods:subLocation
     * field.  This test ensures that that happens properly.
     */
    @Test
    public void testCitationFromCode() throws Exception {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        DescMetadata m = (DescMetadata) u.unmarshal(getClass().getClassLoader().getResourceAsStream("sheet-music-mods.xml"));
        String expectedCitation = "Finale til Baletten, M1 .S444 v.174, no.7, Special Collections, University of Virginia Library, Charlottesville, Va.";
        Assert.assertEquals("Citation should be \"" + expectedCitation + "\".", expectedCitation, m.buildCitation());
    }

    /**
     * Sometimes a citation has to be build using a location from the 
     * mods:location/mods:physicalLocation field.  This test ensures that the
     * method of doing so works.
     */
    @Test
    public void testCitationFromPhysicalLocation() throws Exception {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        DescMetadata m = (DescMetadata) u.unmarshal(getClass().getClassLoader().getResourceAsStream("image-mods.xml"));
        String expectedCitation = "Monticello , Special Collections, University of Virginia Library, Charlottesville, Va.";
        Assert.assertEquals("Citation should be \"" + expectedCitation + "\".", expectedCitation, m.buildCitation());
    }

}