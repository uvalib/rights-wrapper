package edu.virginia.lib.fedora.disseminators.convert;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

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
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CommonsHttpSolrServer;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A servlet that accepts the pid of an image object in fedora and returns the
 * largest disseminable version of that image with a border containing terms of
 * use information as well as a basic bibliographic citation.
 */
public class ConvertServlet extends HttpServlet {
    
    final Logger logger = LoggerFactory.getLogger(ConvertServlet.class);

    private ImageMagickProcess convert;

    private String iiifBaseUrl;
    
    private CloseableHttpClient client;
    
    private SolrServer solr;

    public void init() throws ServletException {
        try {
            iiifBaseUrl = getServletContext().getInitParameter("iiif-base-url");
            convert = new ImageMagickProcess();
            client = HttpClientBuilder.create().build();
            solr = new CommonsHttpSolrServer(getServletContext().getInitParameter("solr-url"));
            ((CommonsHttpSolrServer) solr).setParser(new XMLResponseParser());
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
        String pagePid = req.getParameter("pagePid");
        if (pagePid == null) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String citation;
        try {
            final SolrDocument solrDoc = findSolrDocForId(pid);
            if (solrDoc == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (!canAccessResource(solrDoc, req)) {
                resp.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            citation = getRightsWrapperText(solrDoc);
        } catch (SolrServerException e) {
            citation = "University Of Virginia Library Resource";
        }
        try {
            citation = wrapLongLines(citation, 130, ',');
        } catch (Exception ex) {
            logger.info("Unable to generate citation for " + pid + ", will return an image without a citation.");
        }

        if (req.getParameter("justMetadata") != null) {
            resp.setContentType("text/plain");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getOutputStream().write((citation).getBytes("UTF-8"));
            resp.getOutputStream().close();
        } else {
            File orig = File.createTempFile(pid + "-orig-", ".jpg");
            File framed = File.createTempFile(pid + "-wrapped-", ".jpg");
            File tagged = File.createTempFile(pid + "-wrapped-tagged-", ".jpg");
            try {
                FileOutputStream origOut = new FileOutputStream(orig);
                try {
                    downloadLargeImage(pagePid, origOut);
                } catch (Throwable t) {
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
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
    
    private static final String DEFAULT_TEXT = "University of Virginia Library - search.lib.virginia.edu\nUnder 17USC, Section 107, this single copy was produced for the purposes of private study, scholarship, or research.\nCopyright and other legal restrictions may apply.  Commercial use without permission is prohibited.";
    
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
    
    private String getRightsWrapperText(final SolrDocument doc) {
        if (doc == null) {
            return DEFAULT_TEXT;
        }
        final Object firstWrapperText = doc.getFirstValue("rights_wrapper_display");
        if (firstWrapperText == null) {
            return DEFAULT_TEXT;
        } else {
            return firstWrapperText.toString();
        }
    }
    
    private boolean canAccessResource(SolrDocument doc, HttpServletRequest request) {
        if (doc == null) {
            return false;
        } 
        if (!doc.containsKey("policy_facet")) {
            return true;
        }
        final String policy = doc.getFirstValue("policy_facet").toString();
        if (policy.equals("uva") || policy.equals("uva-lib:2141110")) {
            return request.getRemoteHost().toLowerCase().endsWith(".virginia.edu");
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
