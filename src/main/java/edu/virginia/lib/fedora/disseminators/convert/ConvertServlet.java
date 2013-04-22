package edu.virginia.lib.fedora.disseminators.convert;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    public void init() throws ServletException {
        try {
            convert = new ImageMagickProcess();
            fc = new FedoraClient(new FedoraCredentials(getServletContext().getInitParameter("fedora-url"), getServletContext().getInitParameter("fedora-username"), getServletContext().getInitParameter("fedora-password")));
            policyPattern = Pattern.compile(getServletContext().getInitParameter("policy-pattern"));
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
            citation = getCitationInformation(pid);
        } catch (Exception ex) {
            logger.error("Unable to generate citation for " + pid + "!", ex);
        }

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
                BufferedReader r = new BufferedReader(new InputStreamReader(FedoraClient.getDatastreamDissemination(policyPid, "wrapperText").execute(fc).getEntityInputStream()));
                StringBuffer s = new StringBuffer();
                String line = null;
                while ((line = r.readLine()) != null) {
                    s.append(line + "\n");
                }
                logger.info("Applied policy text from policy object " + policyPid + ".");
                return s.toString();
            } else {
                // unknown policy: fall through with default disclaimer
                logger.warn("Unknown policy URL: " + policyUrl);
            }
        } catch (FedoraClientException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("404")) {
                // no policy text, fall through and display default disclaimer
            } else {
                logger.warn("Error determining policy!", ex);
                throw new ServletException(ex);
            }
        }
        return "Under 17USC, Section 107, this single copy was produced for the purposes of private study, scholarship, or research.\nNo further copies should be made. Copyright and other legal restrictions may apply. Special Collections, University of Virginia Library.";
    }

    public String getCitationInformation(String pid) throws SAXException, IOException, ParserConfigurationException, FedoraClientException, JAXBException {
        // fetch and parse the metadata record
        DescMetadata m = parseDescMetadata(pid);
        if (m.isFullRecord()) {
            return "Title: " + m.getFirstTitle() + "\n"
                    + "URL: http://search.lib.virginia.edu/catalog/" + pid + "/view\n";   
        } else {
            // get the full catalog record
            BufferedReader r = new BufferedReader(new InputStreamReader(FedoraClient.riSearch(getRISearchQueryForParentMetadataRecord(pid)).lang("itql").format("csv").execute(fc).getEntityInputStream()));
            String catalogRecordUri = r.readLine();
            catalogRecordUri = r.readLine();
            if (catalogRecordUri != null) {
                String catlogRecordPid = catalogRecordUri.substring("info:fedora/".length());
                DescMetadata parentMd = parseDescMetadata(catlogRecordPid);
                return "Title: " + parentMd.getFirstTitle() + " -- " + m.getFirstTitle() + "\n"
                        + "URL: http://search.lib.virginia.edu/catalog/" + catlogRecordPid + "/view?page=" + pid + "\n";
            } else {
                // unable to find catalog record, return no citation
                return "UVA Library Resource\nhttp://search.lib.virginia.edu/\n";
            }
        }
    }

    /**
     * There are several relationship chains that link image objects to the 
     * metadata records.  This method is meant to contain all of those 
     * possibilities in a single RISearch query.
     */
    private String getRISearchQueryForParentMetadataRecord(String pid) {
        return "select $catalogRecord from <#ri>"
                + " where <info:fedora/" + pid + "> <http://fedora.lib.virginia.edu/relationships#hasCatalogRecordIn> $catalogRecord"
                + " or ($catalogRecord <http://fedora.lib.virginia.edu/relationships#isMetadataPlaceholderFor> $groupObject" 
                + " and $groupObject <http://fedora.lib.virginia.edu/relationships#hasDigitalRepresentation> <info:fedora/" + pid + ">)";
    }

    /**
     * There are at least two ways to get MODS records from a given object,
     * this method is meant to contain all of those possibilities.
     */
    private DescMetadata parseDescMetadata(String pid) throws JAXBException, FedoraClientException {
        JAXBContext jc = JAXBContext.newInstance(DescMetadata.class);
        Unmarshaller u = jc.createUnmarshaller();
        try {
            // first try the datastream
            return (DescMetadata) u.unmarshal(FedoraClient.getDatastreamDissemination(pid, "descMetadata").execute(fc).getEntityInputStream());
        } catch (FedoraClientException ex) {
            // next try the disseminator (EAD content)
            return (DescMetadata) u.unmarshal(FedoraClient.getDissemination(pid, "uva-lib:descMetadataSDef", "getMetadataAsMODS").execute(fc).getEntityInputStream());
        }
    }
}
