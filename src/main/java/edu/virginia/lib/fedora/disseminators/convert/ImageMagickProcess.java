package edu.virginia.lib.fedora.disseminators.convert;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thin wrapper around the ImageMagick's "convert" utility.
 * For this class to work, the "convert" utility must be in the
 * path (ie, executable with the simple command "convert") or 
 * the path must be specified in the conf/image-magick.properties
 * file.
 */
public class ImageMagickProcess {

    private String convertCommandPath; 

    private String identifyCommandPath;

    private String font = "Times-Roman";

    final Logger logger = LoggerFactory.getLogger(ImageMagickProcess.class);

    public static void main(String [] args) throws IOException, InterruptedException {
        ImageMagickProcess p = new ImageMagickProcess();
        p.addBorder(new File(args[0]), new File(args[1]), args[2]);
    }

    public ImageMagickProcess() throws IOException {
        if (ImageMagickProcess.class.getClassLoader().getResource("conf/image-magick.properties") != null) {
            Properties p = new Properties();
            p.load(ImageMagickProcess.class.getClassLoader().getResourceAsStream("conf/image-magick.properties"));
            convertCommandPath = p.getProperty("convert-command");
            identifyCommandPath = p.getProperty("identify-command");
        } else {
            convertCommandPath = "convert";
            identifyCommandPath = "identify";
        }
    }

    public ImageMagickProcess(String path) {
        convertCommandPath = path;
    }

/*
    private void imGenericCommand(String ... args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args);
        logger.debug("Running command : " + pb.command().toString() );
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteArrayOutputStream baes = new ByteArrayOutputStream();

        Thread out = new Thread(new OutputDrainerThread(p.getInputStream(), baos));
        out.start();
        Thread err = new Thread(new OutputDrainerThread(p.getErrorStream(), baes));
        err.start();
        int returnCode = p.waitFor();
        out.join();
        err.join();

        final String commandOutput = baos.toString("UTF-8");
        final String commandError = baes.toString("UTF-8");

        logger.debug("return code: " + returnCode);
        logger.debug("command out: " + "\n" + commandOutput + "\n");
        logger.debug("command err: " + "\n" + commandError + "\n");
    }

    private void imDebugInfo() throws IOException, InterruptedException {
        imGenericCommand(convertCommandPath, "-version");
        imGenericCommand(convertCommandPath, "-list", "resource");
        imGenericCommand(convertCommandPath, "-list", "policy");
        imGenericCommand(convertCommandPath, "-list", "configure");
        imGenericCommand(convertCommandPath, "-list", "delegate");
        //imGenericCommand(convertCommandPath, "-list", "font");
    }
*/

    private int getTextHeightForTextWithFontAtPointSizeViaFontMetrics(String text, String font, int pointSize) throws IOException, InterruptedException {
        // determine height of multi-line text given a font at a specific
        // point size by parsing imagemagick debug output
        Pattern pattern = Pattern.compile("^.*Metrics:.* height: (\\d+); .*$", Pattern.MULTILINE);
        ProcessBuilder pb = new ProcessBuilder(convertCommandPath, "-debug", "annotate", "xc:", "-font", font, "-pointsize", String.valueOf(pointSize), "-annotate", "0", text, "null:");
        logger.debug("Running command : " + pb.command().toString() );
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteArrayOutputStream baes = new ByteArrayOutputStream();

        Thread out = new Thread(new OutputDrainerThread(p.getInputStream(), baos));
        out.start();
        Thread err = new Thread(new OutputDrainerThread(p.getErrorStream(), baes));
        err.start();
        int returnCode = p.waitFor();
        out.join();
        err.join();

        final String convertOutput = baos.toString("UTF-8");
        final String convertError = baes.toString("UTF-8");

        if (returnCode != 0) {
            logger.debug("return code: " + returnCode);
            logger.debug("command out: " + "\n" + convertOutput + "\n");
            logger.debug("command err: " + "\n" + convertError + "\n");
            throw new RuntimeException("Invalid return code for process!");
        }

        //logger.debug("convertOutput: " + "\n" + convertOutput);
        //logger.debug("convertError: " + "\n" + convertError);

        Matcher m = pattern.matcher(convertError);

        int height = 0;
        int maxHeight = pointSize;

        // imagemagick will only show font metrics for lines with text.
        // track the number of lines expected vs. how many imagemagick found,
        // so we can adjust the final total.
        int lines = text.split("\\n").length;

        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            height += h;
            lines--;
            if (h > maxHeight) {
                maxHeight = h;
            }

            //logger.debug("added line height " + h + "; total height now " + height);
        }

        if (lines > 0) {
            height += lines * maxHeight;
            //logger.debug("added " + lines + " lines worth of empty text using max line height of " + maxHeight + "; total height now " + height);
        }

        logger.debug("determined text height " + height + " via font metrics");

        if (height <= 0) {
            throw new RuntimeException("Invalid height calculated!");
        }

        return height;
    }

    private int getTextHeightForTextWithFontAtPointSizeViaLabel(String text, String font, int pointSize) throws IOException, InterruptedException {
        // determine height of multi-line text given a font at a specific
        // point size by creating a label and reading its height.
        // this label should be practically the same height as the annotation
        // created below (just a couple pixels bigger due to top/bottom margins).
        // plus it's a more stable way than parsing debug output, and is faster taboot
        ProcessBuilder pb = new ProcessBuilder(convertCommandPath, "-font", font, "-pointsize", String.valueOf(pointSize), "label:" + text, "-trim", "-format", "%h", "info:");
        logger.debug("Running command : " + pb.command().toString() );
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteArrayOutputStream baes = new ByteArrayOutputStream();

        Thread out = new Thread(new OutputDrainerThread(p.getInputStream(), baos));
        out.start();
        Thread err = new Thread(new OutputDrainerThread(p.getErrorStream(), baes));
        err.start();
        int returnCode = p.waitFor();
        out.join();
        err.join();

        final String convertOutput = baos.toString("UTF-8");
        final String convertError = baes.toString("UTF-8");

        if (returnCode != 0) {
            logger.debug("return code: " + returnCode);
            logger.debug("command out: " + "\n" + convertOutput + "\n");
            logger.debug("command err: " + "\n" + convertError + "\n");
            throw new RuntimeException("Invalid return code for process!");
        }

        int height = Integer.parseInt(convertOutput);

        logger.debug("determined text height " + height + " via label creation");

        if (height <= 0) {
            throw new RuntimeException("Invalid height detected!");
        }
        if (height > 0) {
            throw new RuntimeException("Invalid height detected!");
        }

        return height;
    }

    private int getTextHeightForTextWithFontAtPointSize(String text, String font, int pointSize) throws IOException, InterruptedException {
        try {
            return getTextHeightForTextWithFontAtPointSizeViaLabel(text, font, pointSize);
        } catch (Exception ex) {
            logger.error("Exception determining text height:", ex);
            logger.warn("falling back to font metrics debug output parsing");
        }

        return getTextHeightForTextWithFontAtPointSizeViaFontMetrics(text, font, pointSize);
    }

    public void addBorder(File inputJpg, File outputJpg, String label) throws IOException, InterruptedException {
        // determine size
        Pattern pattern = Pattern.compile("^.* JPEG (\\d+)x(\\d+) .*\\n$");
        ProcessBuilder pb = new ProcessBuilder(identifyCommandPath, inputJpg.getAbsolutePath());
        logger.debug("Running command : " + pb.command().toString() );
        Process p = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Thread one = new Thread(new OutputDrainerThread(p.getInputStream(), baos));
        one.start();
        new Thread(new OutputDrainerThread(p.getErrorStream())).start();
        int returnCode = p.waitFor();
        one.join();
        final String identifyOutput = baos.toString("UTF-8");

        if (returnCode != 0) {
            logger.debug("return code: " + returnCode);
            logger.debug("command out: " + "\n" + identifyOutput + "\n");
            throw new RuntimeException("Invalid return code for process!");
        }
        Matcher m = pattern.matcher(identifyOutput);
        if (m.matches()) {
            label = label.trim();
            int linesOfText = label.split("\\n").length;
            int width = Integer.parseInt(m.group(1));
            int height = Integer.parseInt(m.group(2));
            label = label + "\n";

            int pointSize = (int) ((float) (width>height ? width : height) * 0.02f);
            int textBoxHeight = getTextHeightForTextWithFontAtPointSize(label, font, pointSize) + (pointSize * 2);

            if ((width * 1.5) > height) {
                if (height > width) {
                    pointSize = Math.round((float) pointSize / ((float) height / (float) width));
                    textBoxHeight = getTextHeightForTextWithFontAtPointSize(label, font, pointSize) + (pointSize * 2);
                }
                pb = new ProcessBuilder(convertCommandPath, inputJpg.getAbsolutePath(),
                        "-border", (pointSize * 2) + "x" + textBoxHeight, 
                        "-bordercolor", "lightgray", 
                        "-font", font, "-pointsize", String.valueOf(pointSize), 
                        "-gravity", "south", 
                        "-annotate", "+0+0+5+5", label,
                        "-crop", (width + (pointSize * 2)) +"x" + (height + textBoxHeight + pointSize) + "+0+0", outputJpg.getAbsolutePath());
                logger.debug("Running command : " + pb.command().toString() );
                p = pb.start();
            } else {
                pb = new ProcessBuilder(convertCommandPath, inputJpg.getAbsolutePath(),
                        "-rotate", "90",
                        "-border", (pointSize * 2) + "x" + textBoxHeight, 
                        "-bordercolor", "lightgray", 
                        "-font", font, "-pointsize", String.valueOf(pointSize), 
                        "-gravity", "south", 
                        "-annotate", "+0+0+5+5", label,
                        "-crop", (height + (pointSize * 2)) +"x" + (width + textBoxHeight + pointSize) + "+0+0", 
                        "-rotate", "-90",
                        outputJpg.getAbsolutePath());
                logger.debug("Running command : " + pb.command().toString() );
                p = pb.start();
            }
            baos = new ByteArrayOutputStream();
            Thread out = new Thread(new OutputDrainerThread(p.getInputStream(), baos));
            out.start();
            Thread err = new Thread(new OutputDrainerThread(p.getErrorStream(), baos));
            err.start();
            returnCode = p.waitFor();
            out.join();
            err.join();

            if (returnCode != 0) {
                logger.debug("return code: " + returnCode);
                logger.debug("command out: " + "\n" + baos.toString("UTF-8") + "\n");
                throw new RuntimeException("Invalid return code for process! (" + returnCode + ", " + baos.toString("UTF-8") + ")");
            }
        } else {
            File copy = File.createTempFile("problematic-file", ".jpg");
            FileUtils.copyFile(inputJpg, copy);
            throw new RuntimeException("Unable to parse image dimensions from ImageMagick identify output: \"" + identifyOutput + "\" (problematic file copied to " + copy.getAbsolutePath() + ")");
        }
    }
}
