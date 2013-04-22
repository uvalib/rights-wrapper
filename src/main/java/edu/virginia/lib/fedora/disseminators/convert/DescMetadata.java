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
}
