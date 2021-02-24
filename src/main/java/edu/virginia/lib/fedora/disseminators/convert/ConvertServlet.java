package edu.virginia.lib.fedora.disseminators.convert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * A servlet that accepts the pid of an image object in fedora and returns the
 * largest disseminable version of that image with a border containing terms of
 * use information as well as a basic bibliographic citation.
 */
public class ConvertServlet extends HttpServlet {

    private static final String VERSION = "2.1.2";

    final Logger logger = LoggerFactory.getLogger(ConvertServlet.class);

    private ImageMagickProcess convert;

    private String iiifBaseUrl;

    private CloseableHttpClient client;

    private String solrUrl;

    private SolrServer solr;

    private String tracksysBaseUrl;

    public void init() throws ServletException {
        try {
            iiifBaseUrl = getServletContext().getInitParameter("iiif-base-url");
            convert = new ImageMagickProcess();
            client = HttpClientBuilder.create().build();
            solrUrl = getServletContext().getInitParameter("solr-url");
            solr = new CommonsHttpSolrServer(solrUrl);
            ((CommonsHttpSolrServer) solr).setParser(new XMLResponseParser());
            tracksysBaseUrl = getServletContext().getInitParameter("tracksys-base-url");
            logger.trace("Servlet startup complete. (version " + VERSION + ")");
        } catch (IOException ex) {
            logger.error("Unable to start ConvertServlet (version " + VERSION + ")", ex);
            throw new ServletException(ex);
        }
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        String referer = req.getHeader("referer");
        if (referer == null) {
            referer = "";
        } else {
            referer = " (referer: " + referer + ")";
        }

        // parse page pid from end of path
        String pathInfo = req.getPathInfo();
        String[] pathParts = pathInfo.split("/");
        final String pagePid = pathParts[pathParts.length-1];

        // convert page pid to metadata pid
        TracksysPidInfo tsPidInfo;
        try {
            tsPidInfo = new TracksysPidInfo(this.tracksysBaseUrl, pagePid);
        } catch (Exception e) {
            logger.error("Exception resolving page pid!", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // convert metadata pid to solr id and get rights statement
        TracksysMetadataInfo tsMetaInfo;
        try {
            tsMetaInfo = new TracksysMetadataInfo(this.tracksysBaseUrl, tsPidInfo.parentMetadataPid);
        } catch (Exception e) {
            logger.error("Exception resolving metadata pid!", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        if (req.getParameter("about") != null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("text/plain");
            IOUtils.write("Rights wrapper service version " + VERSION + "\n\n" + iiifBaseUrl + "\n" + solrUrl, resp.getOutputStream());
            resp.getOutputStream().close();
            return;
        }

        if (tsMetaInfo.catalogKey == "") {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.setContentType("text/plain");
            IOUtils.write("Rights wrapper service version " + VERSION + "\nrequired parameters: pagePid (at end of url path)\noptional parameters: about, justMetadata", resp.getOutputStream());
            resp.getOutputStream().close();
            return;
        }

        // check Solr for access policy
        try {
            final SolrDocument solrDoc = findSolrDocForId(tsMetaInfo.catalogKey);
            if (solrDoc == null) {
                logger.info("Denied request for \"" + tsMetaInfo.catalogKey + "\": 404 solr record not found" + referer);
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (!canAccessResource(solrDoc, req)) {
                logger.debug("Denied request for \"" + tsMetaInfo.catalogKey + "\": unauthorized: " + referer);
                resp.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        } catch (SolrServerException e) {
            // let request through
            // TODO: maybe check rights-ws as a fallback?
        }

        // build full citation from MLA citation plus rights info

        // rights:
        String rights;

        // fallback:
        rights = "University of Virginia Library - search.lib.virginia.edu\nUnder 17USC, Section 107, this single copy was produced for the purposes of private study, scholarship, or research.\nCopyright and other legal restrictions may apply.  Commercial use without permission is prohibited.";

        if (tsMetaInfo.rightsStatement != "") {
            rights = tsMetaInfo.rightsStatement;
        }

        // citation:
        // TODO: get from citations-ws
        String citation = "";

        // fallback:
        citation += tsMetaInfo.title.trim() + ".  ";
        citation += tsMetaInfo.callNumber + ".  ";
        citation += "University of Virginia Library, Charlottesville, VA.";

        // format the citation
        try {
            citation = wrapLongLines(citation, 130, ',');
        } catch (Exception ex) {
            logger.info("Unable to generate citation for " + tsMetaInfo.catalogKey + ", will return an image without a citation.");
        }

        // return result (either metadata or framed image)
        if (req.getParameter("justMetadata") != null) {
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getOutputStream().write((citation).getBytes("UTF-8"));
            resp.getOutputStream().close();
        } else {
            File orig = File.createTempFile(tsMetaInfo.catalogKey + "-orig-", ".jpg");
            File framed = File.createTempFile(tsMetaInfo.catalogKey + "-wrapped-", ".jpg");
            File tagged = File.createTempFile(tsMetaInfo.catalogKey + "-wrapped-tagged-", ".jpg");
            try {
                FileOutputStream origOut = new FileOutputStream(orig);
                try {
                    downloadLargeImage(pagePid, origOut);
                } catch (RuntimeException ex) {
                    if (ex.getMessage() != null && ex.getMessage().startsWith("400")) {
                        logger.debug("Denied request for \"" + pagePid + "\": 404 unable to download image" + referer);
                        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        return;
                    } else {
                        logger.debug("Denied request for \"" + pagePid + "\": " + ex.getMessage() + referer);
                        resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        return;
                    }
                } finally {
                    origOut.close();
                }
                // add the frame
                convert.addBorder(orig, framed, citation);

                // add the exif
                addUserComment(framed, tagged, citation);

                // return the content
                resp.setContentType("image/jpeg");
                resp.setStatus(HttpServletResponse.SC_OK);
                IOUtils.copy(new FileInputStream(tagged), resp.getOutputStream());
                long size = orig.length();
                long end = System.currentTimeMillis();
                logger.info("Serviced request for \"" + pagePid + "\" (" + size + " bytes) in " + (end - start) + "ms." + referer);
            } catch (Exception ex) {
                logger.warn("Denied request for \"" + pagePid + "\": " + ex.getMessage() + referer, ex);
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            } finally {
                orig.delete();
                framed.delete();
                tagged.delete();
            }
        }
    }

    /**
     * Sometimes there are multiple digitized items for a single record.  In these cases, for the purpose of
     * citation building, it's important to identify which one is being displayed.
     * @param solrDoc the solr doc for the record
     * @param itemPid the pid for the individual digitized item within the record (or the pid for the record
     *                in cases where the first item should be selected)
     * @return the index within the list of digitized items of the item corresponding to the given itemPid
     */
/*
    private int computeDigitizedItemPid(final SolrDocument solrDoc, final String itemPid) {
        Collection<Object> altIds = solrDoc.getFieldValues("alternate_id_a");
        if (altIds == null || altIds.isEmpty()) {
            return 0;
        } else {
            final int index = ((List) altIds).indexOf(itemPid);
            if (index == -1) {
                // there are cases where alternate_id_a is used for legacy record names that don't coincide with
                // individual digital representations
                return 0;
            } else {
                return index;
            }
        }
    }
*/

    private SolrDocument findSolrDocForId(final String id) throws SolrServerException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", new String[] { "id:\"" + id + "\"" });
        p.set("rows", 2);

        QueryResponse response = null;
        response = solr.query(p);
        if (response.getResults().size() == 1) {
            return response.getResults().get(0);
        } else {
            return null;
        }
    }

    class TracksysPidInfo {
        String parentMetadataPid;
        String type;
        String title;

        TracksysPidInfo(final String tracksysBaseUrl, final String pagePid) throws ClientProtocolException, IOException, ParseException {
            final String url = tracksysBaseUrl + "pid/" + pagePid;

            HttpGet get = new HttpGet(url);
            try {
                HttpResponse response = client.execute(get);
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException(response.getStatusLine().getStatusCode() + " response from " + url + ".");
                } else {
                    String apiResponse = EntityUtils.toString(response.getEntity());

                    JSONParser jsonParser = new JSONParser();
                    Object parseResult = jsonParser.parse(apiResponse);
                    JSONObject jsonResponse = (JSONObject) parseResult;
                    Object jsonValue;

                    jsonValue = jsonResponse.get("parent_metadata_pid");
                    this.parentMetadataPid = jsonValue.toString();

                    jsonValue = jsonResponse.get("type");
                    this.type = jsonValue.toString();

                    jsonValue = jsonResponse.get("title");
                    this.title = jsonValue.toString();

                    logger.debug("Resolved the page pid " + pagePid + " to " + parentMetadataPid + ".");

                    logger.debug("TracksysPidInfo: parentMetadataPid = [" + parentMetadataPid + "]");
                    logger.debug("TracksysPidInfo: type              = [" + type + "]");
                    logger.debug("TracksysPidInfo: title             = [" + parentMetadataPid + "]");
                }
            } finally {
                get.releaseConnection();
            }
        }
    }

    class TracksysMetadataInfo {
        String catalogKey;
        String callNumber;
        String title;
        String rightsStatement;

        TracksysMetadataInfo(final String tracksysBaseUrl, final String metadataPid) throws ClientProtocolException, IOException, ParseException {
            final String url = tracksysBaseUrl + "metadata/" + metadataPid + "?type=brief";

            HttpGet get = new HttpGet(url);
            try {
                HttpResponse response = client.execute(get);
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException(response.getStatusLine().getStatusCode() + " response from " + url + ".");
                } else {
                    String apiResponse = EntityUtils.toString(response.getEntity());

                    JSONParser jsonParser = new JSONParser();
                    Object parseResult = jsonParser.parse(apiResponse);
                    JSONObject jsonResponse = (JSONObject) parseResult;
                    Object jsonValue;

                    jsonValue = jsonResponse.get("catalogKey");
                    this.catalogKey = jsonValue.toString();

                    jsonValue = jsonResponse.get("callNumber");
                    this.callNumber = jsonValue.toString();

                    jsonValue = jsonResponse.get("title");
                    this.title = jsonValue.toString();

                    jsonValue = jsonResponse.get("rightsStatement");
                    this.rightsStatement = jsonValue.toString();

                    logger.debug("Resolved the metadata pid " + metadataPid + " to " + catalogKey + ".");

                    logger.debug("TracksysMetadataInfo: catalogKey        = [" + catalogKey + "]");
                    logger.debug("TracksysMetadataInfo: callNumber        = [" + callNumber + "]");
                    logger.debug("TracksysMetadataInfo: title             = [" + title + "]");
                    logger.debug("TracksysMetadataInfo: rightsStatement   = [" + rightsStatement + "]");
                }
            } finally {
                get.releaseConnection();
            }
        }
    }

/*
    private String resolveSolrId(final String metadataPid) throws SolrServerException {
        final ModifiableSolrParams p = new ModifiableSolrParams();
        p.set("q", new String[] { "alternate_id_a:\"" + metadataPid + "\"" });
        p.set("rows", 2);

        QueryResponse response = null;
        response = solr.query(p);
        if (response.getResults().size() == 1) {
            final String resolved = String.valueOf(response.getResults().get(0).getFirstValue("id"));
            logger.debug("Resolved the alt-id " + metadataPid + " to " + resolved + ".");
            return resolved;
        } else {
            return metadataPid;
        }
    }
*/

/*
    private String getRightsWrapperText(final SolrDocument doc, final int volumeIndex) {
        if (doc == null) {
            return DEFAULT_TEXT;
        }
        final Object firstWrapperText = ((List) doc.getFieldValues("rights_wrapper_a")).get(volumeIndex);
        if (firstWrapperText == null) {
            return DEFAULT_TEXT;
        } else {
            return firstWrapperText.toString();
        }
    }
*/

    private boolean canAccessResource(SolrDocument doc, HttpServletRequest request) {
        if (doc == null) {
            return false;
        }
        if (!doc.containsKey("policy_a")) {
            return true;
        }
        final String policy = doc.getFirstValue("policy_a").toString();
        if (policy.equals("uva") || policy.equals("uva-lib:2141110")) {
            boolean allow = request.getRemoteHost().toLowerCase().endsWith(".virginia.edu");
            if (!allow) {
                logger.debug("Denying access to \"" + request.getRemoteHost().toLowerCase() + "\" for uva-only content.");
            }
            return allow;
        } else if (policy.equals("public") || policy.equals("uva-lib:2141109")) {
            return true;
        } else {
            return false;
        }
    }

    private void downloadLargeImage(final String pid, OutputStream out) throws ClientProtocolException, IOException {
        final String url = this.iiifBaseUrl + pid + "/full/pct:50/0/default.jpg";
        HttpGet get = new HttpGet(url);
        try {
            HttpResponse response = client.execute(get);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException(response.getStatusLine().getStatusCode() + " response from " + url + ".");
            } else {
                IOUtils.copy(response.getEntity().getContent(), out);
            }
        } finally {
            get.releaseConnection();
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
            ImageMetadata metadata = Imaging.getMetadata(jpegin);
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

}
