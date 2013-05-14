package edu.virginia.lib.fedora.disseminators.convert;

import junit.framework.Assert;

import org.junit.Test;

public class ComponentSummaryTest {

    @Test
    public void testSummaryParsing() throws Exception {
        ComponentSummary s = ComponentSummary.parseBreadcrumbs(getClass().getClassLoader().getResourceAsStream("hierarchy-summary.xml"));
        System.out.println(s);
        Assert.assertEquals("Collection pid is properly parsed.", "uva-lib:2137307", s.getCollectionPid());
        Assert.assertEquals("Collection title is properly parsed.", "Daily Progress Digitized Microfilm", s.getCollectionTitle());
        Assert.assertEquals("Item title is properly parsed.", "Daily Progress, March 04, 1901", s.getItemTitle());
        
    }

}