package edu.virginia.lib.fedora.disseminators.convert;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlValue;

@XmlRootElement(namespace=MarcMetadata.MARC_NS, name="record")
public class MarcMetadata {

    public static final String MARC_NS = "http://www.loc.gov/MARC21/slim";

    @XmlElement(namespace=MarcMetadata.MARC_NS, name="datafield")
    public DataField[] datafields;

    /**
     * Returns the citation from the 524a field in the MARC record or null
     * if that subfield isn't present.
     * @return the citation, or null if none is found
     */
    public String getCitation() {
        for (DataField d : datafields) {
            if ("524".equals(d.tag)) {
                for (SubField s : d.subfields) {
                    if ("a".equals(s.code)) {
                        return s.value;
                    }
                }
            }
        }
        return null;
    }
    
    public static class DataField {

        @XmlAttribute
        public String tag;

        @XmlAttribute
        public String ind1;

        @XmlAttribute
        public String ind2;

        @XmlElement(namespace=MarcMetadata.MARC_NS, name="subfield")
        public SubField[] subfields;
    }

    public static class SubField {

        @XmlAttribute
        public String code;

        @XmlValue
        public String value;
    }
}
