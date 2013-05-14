package edu.virginia.lib.fedora.disseminators.convert;

import junit.framework.Assert;

import org.junit.Test;

public class ConvertServletTest {
    @Test
    public void testWrappingCode() throws Exception {
        String longLine = "This is an example long line of text, with only a couple commas, that contains more than a few characters.";
        Assert.assertEquals("Test wrapping with comma breaks.", "This is an example long line of text,\nwith only a couple commas,\nthat contains more than a few characters.", ConvertServlet.wrapLongLines(longLine, 50, ','));
        Assert.assertEquals("Test wrapping with space breaks.", "This is an example long line of text, with only a\ncouple commas, that contains more than a few\ncharacters.", ConvertServlet.wrapLongLines(longLine, 50, ' '));
        Assert.assertEquals("Test wrapping no good breaks.", "This is an example long line of text, with only a\ncouple commas, that contains more than a few chara\ncters.", ConvertServlet.wrapLongLines(longLine, 50, '|'));
    }
}
