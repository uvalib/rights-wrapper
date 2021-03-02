package edu.virginia.lib.fedora.disseminators.convert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.config.RequestConfig;
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

    private static final String VERSION = "2.2.0";

    final Logger logger = LoggerFactory.getLogger(ConvertServlet.class);

    private ImageMagickProcess convert;
    private CloseableHttpClient client;
    private SolrServer solrCatalog;
    private SolrServer solrImages;

    private String iiifBaseUrl;
    private String solrCatalogUrl;
    private String solrImagesUrl;
    private String tracksysBaseUrl;
    private String virgoBaseUrl;
    private String citationsBaseUrl;
    private String catalogPoolBaseUrl;
    private String imagesPoolBaseUrl;

    private String buildVersion;

    public void init() throws ServletException {
        try {
            iiifBaseUrl = System.getenv("IIIF_BASE_URL");
            solrCatalogUrl = System.getenv("SOLR_CATALOG_URL");
            solrImagesUrl = System.getenv("SOLR_IMAGES_URL");
            tracksysBaseUrl = System.getenv("TRACKSYS_BASE_URL");
            virgoBaseUrl = System.getenv("VIRGO_BASE_URL");
            citationsBaseUrl = System.getenv("CITATIONS_BASE_URL");
            catalogPoolBaseUrl = System.getenv("CATALOG_POOL_BASE_URL");
            imagesPoolBaseUrl = System.getenv("IMAGES_POOL_BASE_URL");

            int connTimeout;
            try {
                connTimeout = Integer.parseInt(System.getenv("HTTP_CONN_TIMEOUT"));
            } catch (NumberFormatException e) {
                connTimeout = 5;
            }

            int readTimeout;
            try {
                readTimeout = Integer.parseInt(System.getenv("HTTP_READ_TIMEOUT"));
            } catch (NumberFormatException e) {
                readTimeout = 30;
            }

            RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(connTimeout * 1000)
                .setConnectionRequestTimeout(connTimeout * 1000)
                .setSocketTimeout(readTimeout * 1000)
                .build();

            client = HttpClientBuilder.create().setDefaultRequestConfig(config).build();

            convert = new ImageMagickProcess();

            solrCatalog = new CommonsHttpSolrServer(solrCatalogUrl);
            ((CommonsHttpSolrServer) solrCatalog).setParser(new XMLResponseParser());

            solrImages = new CommonsHttpSolrServer(solrImagesUrl);
            ((CommonsHttpSolrServer) solrImages).setParser(new XMLResponseParser());

            buildVersion = getBuildVersion();

            logger.trace("Servlet startup complete.");
            logger.trace("[CONFIG] Version               : " + VERSION);
            logger.trace("[CONFIG] Build Version         : " + buildVersion);
            logger.trace("[CONFIG] IIIF Base URL         : " + iiifBaseUrl);
            logger.trace("[CONFIG] Solr Catalog URL      : " + solrCatalogUrl);
            logger.trace("[CONFIG] Solr Images URL       : " + solrImagesUrl);
            logger.trace("[CONFIG] Tracksys Base URL     : " + tracksysBaseUrl);
            logger.trace("[CONFIG] Virgo Base URL        : " + virgoBaseUrl);
            logger.trace("[CONFIG] Citations Base URL    : " + citationsBaseUrl);
            logger.trace("[CONFIG] Catalog Pool Base URL : " + catalogPoolBaseUrl);
            logger.trace("[CONFIG] Images Pool Base URL  : " + imagesPoolBaseUrl);
        } catch (IOException ex) {
            logger.error("Unable to start ConvertServlet (version " + VERSION + ")", ex);
            throw new ServletException(ex);
        }
    }

    private String getBuildVersion() {
        String buildVersion = "unknown";

        try {
            String tagPrefix = "buildtag.";
            File dir = new File("/var/lib/jetty");
            FileFilter fileFilter = new WildcardFileFilter(tagPrefix + "*");
            File[] files = dir.listFiles(fileFilter);
            if (files.length > 0) {
                buildVersion = files[0].getName().replaceAll(tagPrefix, "");
            }
        } catch (Exception ex) {
        }

        return buildVersion;
    }

    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (req.getParameter("about") != null) {
            showAbout(req, resp);
            return;
        }

        // determine which endpoint was requested (if any)
        File f = new File(req.getPathInfo());
        final String endpoint = f.getAbsolutePath();

        // silence these in logs...
        if (endpoint.equals("/favicon.ico")) {
            ignoreHandler(req, resp);
            return;
        }

        logger.debug("GET " + endpoint);

        if (endpoint.equals("/healthcheck")) {
            healthcheckHandler(req, resp);
            return;
        }

        if (endpoint.equals("/version")) {
            versionHandler(req, resp);
            return;
        }

        if (endpoint.startsWith("/api/pid/")) {
            final String pagePid = f.getName();
            pidHandler(req, resp, pagePid);
            return;
        }

        // no match; show usage info
        showUsage(req, resp);
    }

    private void showAbout(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        IOUtils.write("Rights wrapper service version " + VERSION + "\n\n", resp.getOutputStream());
        IOUtils.write("IIIF Base URL         : " + iiifBaseUrl + "\n", resp.getOutputStream());
        IOUtils.write("Solr Catalog URL      : " + solrCatalogUrl + "\n", resp.getOutputStream());
        IOUtils.write("Solr Images URL       : " + solrImagesUrl + "\n", resp.getOutputStream());
        IOUtils.write("Tracksys Base URL     : " + tracksysBaseUrl + "\n", resp.getOutputStream());
        IOUtils.write("Virgo Base URL        : " + virgoBaseUrl + "\n", resp.getOutputStream());
        IOUtils.write("Citations Base URL    : " + citationsBaseUrl + "\n", resp.getOutputStream());
        IOUtils.write("Catalog Pool Base URL : " + catalogPoolBaseUrl + "\n", resp.getOutputStream());
        IOUtils.write("Images Pool Base URL  : " + imagesPoolBaseUrl + "\n", resp.getOutputStream());
        resp.getOutputStream().close();
    }

    private void showUsage(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        resp.setContentType("text/plain");
        IOUtils.write("Rights wrapper service version " + VERSION + "\n\n", resp.getOutputStream());
        IOUtils.write("usage:  GET /api/pid/{pagePID}\n\n", resp.getOutputStream());
        IOUtils.write("optional parameters:\n", resp.getOutputStream());
        IOUtils.write(" * about -- shows configured service URLs\n", resp.getOutputStream());
        IOUtils.write(" * justMetadata -- returns just the image metadata\n", resp.getOutputStream());
        resp.getOutputStream().close();
    }

    private void versionHandler(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        IOUtils.write("{\"build\":\"" + buildVersion + "\"}", resp.getOutputStream());
        resp.getOutputStream().close();
    }

    private void healthcheckHandler(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json");
        IOUtils.write("{}", resp.getOutputStream());
        resp.getOutputStream().close();
    }

    private void ignoreHandler(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        resp.getOutputStream().close();
    }

    private void pidHandler(HttpServletRequest req, HttpServletResponse resp, final String pagePid) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        String referer = req.getHeader("referer");
        if (referer == null) {
            referer = "";
        } else {
            referer = " (referer: " + referer + ")";
        }

        // look up pid info for this page in tracksys
        TracksysPid tsPid;
        try {
            tsPid = new TracksysPid(tracksysBaseUrl, pagePid);
            if (tsPid.pid.equals("")) {
                logger.info("Pid " + pagePid + " not found in Tracksys.");
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (!tsPid.type.equals("master_file")) {
                logger.info("Pid " + pagePid + " is not a master file.");
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        } catch (Exception e) {
            logger.error("Exception querying Tracksys pid info:", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // look up metadata info for this page in tracksys
        TracksysMetadata tsMeta;
        try {
            tsMeta = new TracksysMetadata(tracksysBaseUrl, tsPid.parentMetadataPid);
            if (tsMeta.pid.equals("")) {
                logger.info("Pid " + tsPid.parentMetadataPid + " not found in Tracksys.");
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        } catch (Exception e) {
            logger.error("Exception querying Tracksys metadata info:", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return;
        }

        // set up item-dependent service urls/parameters
        String solrId;
        SolrServer solr;
        String poolBaseUrl;

        if (!tsMeta.catalogKey.equals("")) {
            logger.debug("Using catalog core parameters");
            solrId = tsMeta.catalogKey;
            solr = solrCatalog;
            poolBaseUrl = catalogPoolBaseUrl;
        } else {
            logger.debug("Using images core parameters");
            solrId = pagePid;
            solr = solrImages;
            poolBaseUrl = imagesPoolBaseUrl;
        }

        // check Solr for access policy
        try {
            final SolrDocument solrDoc = findSolrDocForId(solr, solrId);
            if (solrDoc == null) {
                logger.info("Denied request for \"" + solrId + "\": 404 solr record not found" + referer);
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (!canAccessResource(solrDoc, req)) {
                logger.debug("Denied request for \"" + solrId + "\": unauthorized: " + referer);
                resp.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        } catch (SolrServerException e) {
            // let request through
            // TODO: maybe check rights-ws as a fallback?
        }

        // build full citation from MLA citation plus rights info

        // citation:
        String citation;
        try {
            citation = getCitation(poolBaseUrl, solrId);

            logger.debug("Using provided citation");

            // add call number to help identify copy
            if (!tsMeta.callNumber.equals("")) {
                citation += "\n" + tsMeta.callNumber;
            }
        } catch (Exception e) {
            logger.error("Exception generating citation:", e);

            logger.debug("generating fallback citation");

            // emulate old style citation
            citation = "";

            if (!tsMeta.title.equals("")) {
                citation += tsMeta.title.replaceAll("\\.$","") + ".  ";
            }
            if (!tsMeta.callNumber.equals("")) {
                citation += tsMeta.callNumber + ".  ";
            }
            citation += "University of Virginia Library, Charlottesville, VA.";
        }

        // rights:
        String rights;
        if (!tsMeta.rightsStatement.equals("")) {
            logger.debug("Using provided rights statement");
            rights = tsMeta.rightsStatement;
        } else {
            logger.debug("generating fallback rights statement");
            rights = "University of Virginia Library - search.lib.virginia.edu\nUnder 17USC, Section 107, this single copy was produced for the purposes of private study, scholarship, or research.\nCopyright and other legal restrictions may apply.  Commercial use without permission is prohibited.";
        }

        // virgo url:
        String virgoUrl = virgoBaseUrl + solrId;

        // put it all together...
        String fullCitation = citation + "\n" + virgoUrl + "\n\n" + rights;

        // format the citation
        try {
            fullCitation = wrapLongLines(fullCitation, 130, ',', ' ');
        } catch (Exception ex) {
            logger.info("Unable to generate citation for " + solrId + ", will return an image without a citation.");
        }

        // return result (either metadata or framed image)
        if (req.getParameter("justMetadata") != null) {
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getOutputStream().write((fullCitation).getBytes("UTF-8"));
            resp.getOutputStream().close();
        } else {
            File orig = File.createTempFile(solrId + "-orig-", ".jpg");
            File framed = File.createTempFile(solrId + "-wrapped-", ".jpg");
            File tagged = File.createTempFile(solrId + "-wrapped-tagged-", ".jpg");
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
                logger.debug("[Add image frame]");
                convert.addBorder(orig, framed, fullCitation);

                // add the exif
                logger.debug("[Add image exif]");
                addUserComment(framed, tagged, fullCitation);

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

        logger.debug("Done!");
    }

    private SolrDocument findSolrDocForId(final SolrServer solr, final String id) throws SolrServerException {
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

    class TracksysPid {
        private String pid = "";
        private String type = "";
        private String parentMetadataPid = "";

        TracksysPid(final String tracksysBaseUrl, final String pagePid) throws ClientProtocolException, IOException, ParseException, RuntimeException {
            final String url = tracksysBaseUrl + "pid/" + pagePid;

            HttpGet get = new HttpGet(url);
            try {
                logger.debug("[pid lookup] : " + url);
                HttpResponse response = client.execute(get);

                if (response.getStatusLine().getStatusCode() == 404) {
                    return;
                }

                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException(response.getStatusLine().getStatusCode() + " response from " + url + ".");
                }

                String apiResponse = EntityUtils.toString(response.getEntity());

                JSONParser jsonParser = new JSONParser();
                Object parseResult = jsonParser.parse(apiResponse);
                JSONObject jsonResponse = (JSONObject) parseResult;
                Object jsonValue;

                // required values
                jsonValue = jsonResponse.get("pid");
                pid = jsonValue.toString();

                // optional values
                jsonValue = jsonResponse.get("type");
                if (jsonValue != null) {
                    type = jsonValue.toString();
                }

                jsonValue = jsonResponse.get("parent_metadata_pid");
                if (jsonValue != null) {
                    parentMetadataPid = jsonValue.toString();
                }

                logger.debug("    pid               = [" + pid + "]");
                logger.debug("    type              = [" + type + "]");
                logger.debug("    parentMetadataPid = [" + parentMetadataPid + "]");
            } finally {
                get.releaseConnection();
            }
        }
    }

    class TracksysMetadata {
        private String pid = "";
        private String catalogKey = "";
        private String callNumber = "";
        private String title = "";
        private String rightsStatement = "";

        TracksysMetadata(final String tracksysBaseUrl, final String metadataPid) throws ClientProtocolException, IOException, ParseException, RuntimeException {
            final String url = tracksysBaseUrl + "metadata/" + metadataPid + "?type=brief";

            HttpGet get = new HttpGet(url);
            try {
                logger.debug("[metadata lookup] : " + url);
                HttpResponse response = client.execute(get);

                if (response.getStatusLine().getStatusCode() == 404) {
                    return;
                }

                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException(response.getStatusLine().getStatusCode() + " response from " + url + ".");
                }

                String apiResponse = EntityUtils.toString(response.getEntity());

                JSONParser jsonParser = new JSONParser();
                Object parseResult = jsonParser.parse(apiResponse);
                JSONObject jsonResponse = (JSONObject) parseResult;
                Object jsonValue;

                // required values
                jsonValue = jsonResponse.get("pid");
                pid = jsonValue.toString();

                // optional values
                jsonValue = jsonResponse.get("catalogKey");
                if (jsonValue != null) {
                    catalogKey = jsonValue.toString();
                }

                jsonValue = jsonResponse.get("callNumber");
                if (jsonValue != null) {
                    callNumber = jsonValue.toString();
                }

                jsonValue = jsonResponse.get("title");
                if (jsonValue != null) {
                    title = jsonValue.toString();
                }

                jsonValue = jsonResponse.get("rightsStatement");
                if (jsonValue != null) {
                    rightsStatement = jsonValue.toString();
                }

                logger.debug("    pid             = [" + pid + "]");
                logger.debug("    catalogKey      = [" + catalogKey + "]");
                logger.debug("    callNumber      = [" + callNumber + "]");
                logger.debug("    title           = [" + title + "]");
                logger.debug("    rightsStatement = [" + rightsStatement + "]");
            } finally {
                get.releaseConnection();
            }
        }
    }

    private String getCitation(final String poolBaseUrl, final String id) throws ClientProtocolException, IOException, URISyntaxException, RuntimeException {
        String queryParams = "";
        queryParams += "?item=" + URLEncoder.encode(poolBaseUrl + id, StandardCharsets.UTF_8.toString());
        queryParams += "&inline=1";
        queryParams += "&nohtml=1";

        final String url = citationsBaseUrl + queryParams;

        HttpGet get = new HttpGet(url);
        try {
            logger.debug("[Citation generation] : " + url);
            HttpResponse response = client.execute(get);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new RuntimeException(response.getStatusLine().getStatusCode() + " response from " + url + ".");
            } else {
                String citation = EntityUtils.toString(response.getEntity());
                logger.debug("    citation: [" + citation + "]");
                return citation;
            }
        } finally {
            get.releaseConnection();
        }
    }

    private boolean canAccessResource(SolrDocument doc, HttpServletRequest request) {
        if (doc == null) {
            return false;
        }
        if (!doc.containsKey("policy_a")) {
            return true;
        }
        final String policy = doc.getFirstValue("policy_a").toString();
        if (policy.equals("uva")) {
            boolean allow = request.getRemoteHost().toLowerCase().endsWith(".virginia.edu");
            if (!allow) {
                logger.debug("Denying access to \"" + request.getRemoteHost().toLowerCase() + "\" for uva-only content.");
            }
            return allow;
        } else if (policy.equals("public")) {
            return true;
        } else {
            return false;
        }
    }

    private void downloadLargeImage(final String pid, OutputStream out) throws ClientProtocolException, IOException, RuntimeException {
        final String url = iiifBaseUrl + pid + "/full/pct:50/0/default.jpg";
        HttpGet get = new HttpGet(url);
        try {
            logger.debug("[IIIF download] : " + url);
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
    static String wrapLongLines(String text, int maxLength, char breakpoint1, char breakpoint2) {
        StringBuffer wrapped = new StringBuffer();
        for (String s : text.split("\n")) {
            while (s.length() > maxLength) {
                boolean found = false;
                for (int i = maxLength - 1; i > 0; i --) {
                    if (s.charAt(i) == breakpoint1) {
                        wrapped.append(s.substring(0, i+1).trim());
                        wrapped.append("\n");
                        s = s.substring(i+1);
                        found = true;
                        break;
                    }
                }
                if (!found && breakpoint1 != breakpoint2) {
                    // try again using secondary breakpoint character
                    for (int i = maxLength - 1; i > 0; i --) {
                        if (s.charAt(i) == breakpoint2) {
                            wrapped.append(s.substring(0, i+1).trim());
                            wrapped.append("\n");
                            s = s.substring(i+1);
                            found = true;
                            break;
                        }
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
