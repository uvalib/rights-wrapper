package edu.virginia.lib.fedora.disseminators.convert;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;

@XmlRootElement(namespace=DescMetadata.MODS_NS, name="mods")
public class DescMetadata {

    public static final String MODS_NS = "http://www.loc.gov/mods/v3";

    @XmlElement(namespace=DescMetadata.MODS_NS, name="identifier")
    public Identifier[] identifier;

    @XmlElement(namespace=DescMetadata.MODS_NS, name="titleInfo")
    public TitleInfo[] titleInfo;

    @XmlElement(namespace=DescMetadata.MODS_NS, name="classification")
    public String[] classification;

    @XmlElement(namespace=DescMetadata.MODS_NS, name="location")
    public Location[] location;

    public boolean isFullRecord() {
        for (Identifier i : identifier) {
            if ("Accessible index record displayed in VIRGO".equals(i.displayLabel) && "yes".equals(i.invalid)) {
                return false;
            }
        }
        return true;
    }

    public String getFirstTitle() {
        for (TitleInfo t : titleInfo) {
            return t.title;
        }
        return "";
    }

    /**
     * Generates a citation by appending the library location to the call
     * number to the title.  This citation is suitable for objects that don't
     * have a MARC record with a citation field.
     */
    public String buildCitation() {
        String title = getFirstTitle();
        String callNumber = classification != null ? classification[0] : null;
        String location = detectCopyLocation();
        return joinStrings(", ", title, callNumber, location);
    }

    private String detectCopyLocation() {
        if (location != null) {
            for (Location l : location) {
                // first try "location/physicalLocation"
                if (l.physicalLocation != null && l.physicalLocation.length() > 0) {
                    return l.physicalLocation;
                }

                // next try location/holdingSimple/copyInformation/subLocation with a
                // known code value
                if (l.holdings != null) {
                    for (HoldingSimple h : l.holdings) {
                        if (h.copyInformation != null) {
                            for (CopyInformation c : h.copyInformation) {
                                if ("SPEC-COLL".equals(c.subLocation)) {
                                    return "Special Collections, University of Virginia Library, Charlottesville, Va.";
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String joinStrings(String delimiter, String ... elements) {
        StringBuffer sb = new StringBuffer();
        for (String element : elements) {
            if (element != null && element.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(delimiter);
                }
                sb.append(element);
            }
        }
        return sb.toString();
    }

    public static class Identifier {
        @XmlValue
        public String value;

        @XmlAttribute
        public String displayLabel;

        @XmlAttribute
        public String invalid;
    }

    public static class TitleInfo {
        @XmlElement(namespace=DescMetadata.MODS_NS)
        public String title;
    }

    public static class Location {
        @XmlElement(namespace=DescMetadata.MODS_NS)
        public String physicalLocation;

        @XmlElement(namespace=DescMetadata.MODS_NS, name="holdingSimple")
        public HoldingSimple[] holdings;
    }

    public static class HoldingSimple {
        @XmlElement(namespace=DescMetadata.MODS_NS, name="copyInformation")
        public CopyInformation[] copyInformation;
    }

    public static class CopyInformation {
        @XmlElement(namespace=DescMetadata.MODS_NS, name="subLocation")
        public String subLocation;
    }
}
