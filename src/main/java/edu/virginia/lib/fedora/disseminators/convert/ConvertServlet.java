package edu.virginia.lib.fedora.disseminators.convert;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.IImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import com.yourmediashelf.fedora.client.FedoraClient;
import com.yourmediashelf.fedora.client.FedoraClientException;
import com.yourmediashelf.fedora.client.FedoraCredentials;
import com.yourmediashelf.fedora.generated.access.DatastreamType;

/**
 * A servlet that accepts the pid of an image object in fedora and returns the
 * largest disseminable version of that image with a border containing terms of
 * use information as well as a basic bibliographic citation.
 */
public class ConvertServlet extends HttpServlet {

    final Logger logger = LoggerFactory.getLogger(ConvertServlet.class);

    private Pattern policyPattern;

    private ImageMagickProcess convert;

    private FedoraClient fc;

    private String defaultPolicyPid;

    public void init() throws ServletException {
        try {
            convert = new ImageMagickProcess();
            fc = new FedoraClient(new FedoraCredentials(getServletContext().getInitParameter("fedora-url"), getServletContext().getInitParameter("fedora-username"), getServletContext().getInitParameter("fedora-password")));
            policyPattern = Pattern.compile(getServletContext().getInitParameter("policy-pattern"));
            defaultPolicyPid = getServletContext().getInitParameter("default-policy-pid");
            if (defaultPolicyPid == null) {
                logger.warn("No default-policy-pid specified!");
            }
            logger.trace("Servlet startup complete.");
        } catch (IOException ex) {
            logger.error("Unable to start ConvertServlet", ex);
            throw new ServletException(ex);
        }
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String pid = req.getParameter("pid");
        if (pid == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String disclaimer = getPolicyDisclaimer(pid);
        String citation = "";
        try {
            citation = wrapLongLines(getCitationInformation(pid), 130, ',');
        } catch (Exception ex) {
            logger.error("Unable to generate citation for " + pid + "!", ex);
        }

        if (req.getParameter("justMetadata") != null) {
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getOutputStream().write((citation + "\n" + disclaimer).getBytes("UTF-8"));
            resp.getOutputStream().close();
        } else {
            File orig = File.createTempFile(pid + "-orig-", ".jpg");
            File framed = File.createTempFile(pid + "-wrapped-", ".jpg");
            File tagged = File.createTempFile(pid + "-wrapped-tagged-", ".jpg");
            try {
                System.out.println(pid + " " + getServletContext().getInitParameter("image-service") + " " + getServletContext().getInitParameter("image-method"));
                IOUtils.copy(FedoraClient.getDissemination(pid, getServletContext().getInitParameter("image-service"), getServletContext().getInitParameter("image-method")).methodParam("rotate", "").methodParam("scale", "0.5").execute(fc).getEntityInputStream(), new FileOutputStream(orig));
    
                // add the frame
                convert.addBorder(orig, framed, citation + "\n" + disclaimer);
    
                // add the exif
                addUserComment(framed, tagged, citation + "\n" + disclaimer);
    
                // return the content
                resp.setContentType("image/jpeg");
                resp.setStatus(HttpServletResponse.SC_OK);
                IOUtils.copy(new FileInputStream(tagged), resp.getOutputStream());
            } catch (Exception ex) {
                throw new ServletException(ex);
            } finally {
                long size = orig.length();
                orig.delete();
                framed.delete();
                tagged.delete();
                long end = System.currentTimeMillis();
                logger.info("Serviced request for \"" + pid + "\" (" + size + " bytes) in " + (end - start) + "ms.");
            }
        }
    }

    /**
     * Iterates over lines of text (as delimited by the \\n character) and 
     * returns a new String that is a copy of the provided text with line
     * breaks added after the closest breakpoint before the string length 
     * exceeds the maxLength.  Whitespace is trimmed from each new line.
     */
    static String wrapLongLines(String text, int maxLength, char breakpoint) {
        StringBuffer wrapped = new StringBuffer();
        for (String s : text.split("\n")) {
            while (s.length() > maxLength) {
                boolean found = false;
                for (int i = maxLength - 1; i > 0; i --) {
                    if (s.charAt(i) == breakpoint) {
                        wrapped.append(s.substring(0, i+1).trim());
                        wrapped.append("\n");
                        s = s.substring(i+1);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // no good breakpoint found, break it at the maxLength
                    wrapped.append(s.substring(0, maxLength).trim());
                    wrapped.append("\n");
                    s = s.substring(maxLength);
                }
            }
            wrapped.append(s.trim());
            wrapped.append("\n");
        }
        return wrapped.toString().trim();
    }

    public void addUserComment(File jpegin, File jpegout, String comment) throws IOException, ImageReadException, ImageWriteException {
        OutputStream os = null;
        try {
            TiffOutputSet outputSet = null;

            // note that metadata might be null if no metadata is found.
            IImageMetadata metadata = Imaging.getMetadata(jpegin);
            JpegImageMetadata jpegMetadata = (JpegImageMetadata) metadata;
            if (null != jpegMetadata) {
                // note that exif might be null if no Exif metadata is found.
                TiffImageMetadata exif = jpegMetadata.getExif();

                if (null != exif) {
                    // TiffImageMetadata class is immutable (read-only).
                    // TiffOutputSet class represents the Exif data to write.
                    //
                    // Usually, we want to update existing Exif metadata by
                    // changing
                    // the values of a few fields, or adding a field.
                    // In these cases, it is easiest to use getOutputSet() to
                    // start with a "copy" of the fields read from the image.
                    outputSet = exif.getOutputSet();
                }
            }

            // if file does not contain any exif metadata, we create an empty
            // set of exif metadata. Otherwise, we keep all of the other
            // existing tags.
            if (null == outputSet) {
                outputSet = new TiffOutputSet();
            }

            // Example of how to add a field/tag to the output set.
            //
            // Note that you should first remove the field/tag if it already
            // exists in this directory, or you may end up with duplicate
            // tags. See above.
            //
            // Certain fields/tags are expected in certain Exif directories;
            // Others can occur in more than one directory (and often have a
            // different meaning in different directories).
            //
            // TagInfo constants often contain a description of what
            // directories are associated with a given tag.
            //
            // see
            // org.apache.commons.sanselan.formats.tiff.constants.AllTagConstants
            //
            final TiffOutputDirectory exifDirectory = outputSet.getOrCreateExifDirectory();
 
            // make sure to remove old value if present (this method will
            // not fail if the tag does not exist).
            exifDirectory.removeField(ExifTagConstants.EXIF_TAG_USER_COMMENT);
            exifDirectory.add(ExifTagConstants.EXIF_TAG_USER_COMMENT, comment);

            os = new BufferedOutputStream(new FileOutputStream(jpegout));
            new ExifRewriter().updateExifMetadataLossless(jpegin, os, outputSet);
        } finally {
            if (os != null) {
                os.close();
            }
        }
    }
    
    public String getPolicyDisclaimer(String pid) throws IOException, ServletException {
        try {
            String policyUrl = FedoraClient.getDatastream(pid, "POLICY").execute(fc).getDatastreamProfile().getDsLocation();
            Matcher m = policyPattern.matcher(policyUrl);
            if (m.matches()) {
                String policyPid = m.group(1);
                logger.info("Request for " + pid + " is affected by POLICY " + policyPid + ".");
                return getPolicyWrapperText(policyPid);
            } else {
                // unknown policy: fall through with default disclaimer
                logger.warn("Unknown policy URL: " + policyUrl);
            }
        } catch (FedoraClientException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("404")) {
                // no policy text, fetch the default policy text
                if (defaultPolicyPid != null) {
                    try {
                        return getPolicyWrapperText(defaultPolicyPid);
                    } catch (FedoraClientException ex2) {
                        logger.error("Unable to fetch default policy from " + defaultPolicyPid + "!", ex2);
                        // fall through and display the last resort default
                    }
                } else {
                    // fall through and display the last resort default
                }
            } else {
                logger.warn("Error determining policy!", ex);
                throw new ServletException(ex);
            }
        }
        return "Under 17USC, Section 107, this single copy was produced for the purposes of private study, scholarship, or research.\nCopyright and other legal restrictions may apply.  Commercial use without permission is prohibited.\nUniversity of Virginia Library.";
    }

    private String getPolicyWrapperText(String policyPid) throws IOException, FedoraClientException {
        BufferedReader r = new BufferedReader(new InputStreamReader(FedoraClient.getDatastreamDissemination(policyPid, "wrapperText").execute(fc).getEntityInputStream()));
        StringBuffer s = new StringBuffer();
        String line = null;
        while ((line = r.readLine()) != null) {
            s.append(line + "\n");
        }
        logger.info("Applied policy text from policy object " + policyPid + ".");
        return s.toString();
    }

    /**
     * Queries the repository for a bibliographic citation for an object with
     * the given pid.  The current implementation determins this citation the
     * following way:
     * 
     * 1.  If the item represents the full record...
     * 1a.   And it has a MARC record with a citation, that citation is used
     * @param pid
     * @return
     * @throws SAXException
     * @throws IOException
     * @throws ParserConfigurationException
     * @throws FedoraClientException
     * @throws JAXBException
     */
    public String getCitationInformation(String pid) throws SAXException, IOException, ParserConfigurationException, FedoraClientException, JAXBException {
        // fetch and parse the metadata record
        DescMetadata m = parseMODSDatastream(pid);
        if (m == null) {
            // everything is expected to have a MODS record at the digital 
            // image level, but if for some reason we can't get it, return
            // the default citation.
            return "UVA Library Resource\nhttp://search.lib.virginia.edu/\n";
        }

        Map<String, List<String>> catalogRecordMap = getParentMetadataRecordAndContentModels(pid);
        if (catalogRecordMap.size() != 1) {
            // unable to determine a single item level record, return a
            // mostly-worthless citation.
            return "Citation: " + m.buildCitation() + "\n"
            + "Online Access: http://search.lib.virginia.edu/catalog/" + (m.isFullRecord() ? pid : "") + "\n";
        } else {
            String catalogRecordPid = catalogRecordMap.keySet().iterator().next();
            if (catalogRecordMap.get(catalogRecordPid).contains("uva-lib:eadItemCModel")) {
                // Hierarchical DL content
                // 1. parse the uva-lib:hierarchicalMetadataSDef/getSummary response
                ComponentSummary s = ComponentSummary.parseBreadcrumbs(FedoraClient.getDissemination(catalogRecordPid, "uva-lib:hierarchicalMetadataSDef", "getSummary").execute(fc).getEntityInputStream());
                // 2. return a citation built from that information
                return "Citation: " + m.getFirstTitle() + ", " + s.getItemTitle() + ", " + s.getCollectionTitle() + "\n"
                        + "Collection Record: http://search.lib.virginia.edu/catalog/" + s.getCollectionPid() + "\n"
                        + "Online Access: http://search.lib.virginia.edu/catalog/" + catalogRecordPid + "/view?page=" + pid + "\n";
            } else {
                // Tranditional DL content
                DescMetadata parentMd = parseMODSDatastream(catalogRecordPid);
                if (parentMd != null) {
                    MarcMetadata parentMarc = parseMARCDatastream(catalogRecordPid);
                    String citation = null;
                    if (parentMarc != null && parentMarc.getCitation() != null) {
                        citation = parentMarc.getCitation();
                    } else {
                        citation = parentMd.buildCitation();
                    }
                    return "Citation: " + citation + "\n"
                            + "Catalog Record: http://search.lib.virginia.edu/catalog/" + catalogRecordPid + "\n"
                            + "Online Access: http://search.lib.virginia.edu/catalog/" + (m.isFullRecord() ? pid : catalogRecordPid + "/view?page=" + pid) + "\n"
                            + "Page Title: " + m.getFirstTitle() + "\n";
                } else {
                    return "UVA Library Resource\nhttp://search.lib.virginia.edu/\n";
                }
            }

        }
    }

    /**
     * There are several relationship chains that link image objects to the 
     * metadata records.  This method is meant to contain all of those 
     * possibilities in a single RISearch query.
     * @throws FedoraClientException 
     * @throws IOException 
     */
    private Map<String, List<String>> getParentMetadataRecordAndContentModels(String pid) throws FedoraClientException, IOException {
        String itqlQuery = "select $catalogRecord $model from <#ri>"
                + " where (<info:fedora/" + pid + "> <http://fedora.lib.virginia.edu/relationships#hasCatalogRecordIn> $catalogRecord"
                + " or $catalogRecord <http://fedora.lib.virginia.edu/relationships#hasDigitalRepresentation> <info:fedora/" + pid + ">" 
                + " or <info:fedora/" + pid + "> <http://fedora.lib.virginia.edu/relationships#isDigitalRepresentationOf> $catalogRecord)"
                + " and $catalogRecord <info:fedora/fedora-system:def/model#hasModel> $model";

        BufferedReader r = new BufferedReader(new InputStreamReader(FedoraClient.riSearch(itqlQuery).lang("itql").format("csv").execute(fc).getEntityInputStream()));
        String line = r.readLine();
        Map<String, List<String>> map = new HashMap<String, List<String>>();
        while ((line = r.readLine()) != null) {
            String[] cols = line.split(",");
            String parentPid = cols[0].substring("info:fedora/".length());
            String parentCmodel = cols[1].substring("info:fedora/".length());
            List<String> cmodels = map.get(parentPid);
            if (cmodels == null) {
                cmodels = new ArrayList<String>();
                map.put(parentPid, cmodels);
            }
            cmodels.add(parentCmodel);
        }
        return map;
       
    }

    /**
     * Parses the "descMetadata" datastream from an object as a MODS record.
     * @return a DescMetadata object or null if unable to read the datastream 
     * as a MODS record.
     */ 
    private DescMetadata parseMODSDatastream(String pid) throws JAXBException, FedoraClientException {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        try {
            return (DescMetadata) u.unmarshal(FedoraClient.getDatastreamDissemination(pid, "descMetadata").execute(fc).getEntityInputStream());
        } catch (FedoraClientException ex) {
            return null;
        }
    }

    private MarcMetadata parseMARCDatastream(String pid) throws JAXBException {
        JAXBContext jc = JAXBContext.newInstance(MarcMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        try {
            return (MarcMetadata) u.unmarshal(FedoraClient.getDatastreamDissemination(pid, "MARC").execute(fc).getEntityInputStream());
        } catch (FedoraClientException ex) {
            return null;
        }
    }
}
