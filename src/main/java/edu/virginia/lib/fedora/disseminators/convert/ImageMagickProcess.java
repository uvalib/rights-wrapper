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
            int textBoxHeight = (pointSize * (linesOfText + 1));

            if ((width * 1.5) > height) {
                if (height > width) {
                    pointSize = Math.round((float) pointSize / ((float) height / (float) width));
                }
                pb = new ProcessBuilder(convertCommandPath, inputJpg.getAbsolutePath(),
                        "-border", (pointSize * 2) + "x" + textBoxHeight, 
                        "-bordercolor", "lightgray", 
                        "-font", "Times-Roman", "-pointsize", String.valueOf(pointSize), 
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
                        "-font", "Times-Roman", "-pointsize", String.valueOf(pointSize), 
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
                throw new RuntimeException("Invalid return code for process! (" + returnCode + ", " + baos.toString("UTF-8") + ")");
            }
        } else {
            File copy = File.createTempFile("problematic-file", ".jpg");
            FileUtils.copyFile(inputJpg, copy);
            throw new RuntimeException("Unable to parse image dimensions from ImageMagick identify output: \"" + identifyOutput + "\" (problematic file copied to " + copy.getAbsolutePath() + ")");
        }
    }
}
