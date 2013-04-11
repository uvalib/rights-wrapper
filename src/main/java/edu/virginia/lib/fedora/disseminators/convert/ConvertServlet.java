package edu.virginia.lib.fedora.disseminators.convert;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        String citation = getCitationInformation(pid);

        File orig = File.createTempFile(pid + "-orig-", ".jpg");
        File result = File.createTempFile(pid + "-wrapped-", ".jpg");
        try {
            IOUtils.copy(FedoraClient.getDissemination(pid, getServletContext().getInitParameter("image-service"), getServletContext().getInitParameter("image-method")).methodParam("rotate", "").methodParam("scale", "0.5").execute(fc).getEntityInputStream(), new FileOutputStream(orig));
            convert.addBorder(orig, result, citation + "\n" + disclaimer);
            addEXIFTags(result);
            resp.setContentType("image/jpeg");
            resp.setStatus(HttpServletResponse.SC_OK);
            IOUtils.copy(new FileInputStream(result), resp.getOutputStream());
        } catch (FedoraClientException ex) {
            throw new ServletException(ex);
        } catch (InterruptedException ex) {
            throw new ServletException(ex);
        } finally {
            long size = orig.length();
            orig.delete();
            result.delete();
            long end = System.currentTimeMillis();
            logger.info("Serviced request for \"" + pid + "\" (" + size + " bytes) in " + (end - start) + "ms.");
        }
    }

    public void addEXIFTags(File jpeg) {
        // TODO: implement this method
    }
    
    public String getPolicyDisclaimer(String pid) {
        /*
         * TODO: Ultimately this text will come from the POLICY object in
         *       fedora, but right now that text isn't present there.
        try {
            String policyUrl = FedoraClient.getDatastream(pid, "POLICY").execute(fc).getDatastreamProfile().getDsLocation();
            Matcher m = policyPattern.matcher(policyUrl);
            if (m.matches()) {
                
            } else {
                // unknown policy
            }
        } catch (FedoraClientException ex) {
            // if the datstream doesn't exist... use the default disclaimer

            // otherwise return an error
        }
        */
        return "Under 17USC, Section 107, this single copy was produced for the purposes of private study, scholarship, or research.\nNo further copies should be made. Copyright and other legal restrictions may apply. Special Collections, University of Virginia Library.";
    }

    public String getCitationInformation(String pid) {
        // TODO: add more information here
        return "http://search.lib.virginia.edu/catalog/" + pid + "\n";
    }
}
