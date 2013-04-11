package edu.virginia.lib.fedora.disseminators.convert;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        Process p = new ProcessBuilder(identifyCommandPath, inputJpg.getAbsolutePath()).start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new Thread(new OutputDrainerThread(p.getInputStream(), baos)).start();
        new Thread(new OutputDrainerThread(p.getErrorStream())).start();
        int returnCode = p.waitFor();
        if (returnCode != 0) {
            throw new RuntimeException("Invalid return code for process!");
        }
        Matcher m = pattern.matcher(baos.toString("UTF-8"));
        if (m.matches()) {
            int width = Integer.parseInt(m.group(1));
            int height = Integer.parseInt(m.group(2));
            
            int pointSize = (int) ((float) width * 0.014f);
            int bottomBorderHeight = pointSize * 6;

            p = new ProcessBuilder(convertCommandPath, inputJpg.getAbsolutePath(),
                        "-border", "20x" + bottomBorderHeight, 
                        "-bordercolor", "lightgray", 
                        "-font", "Times-Roman", "-pointsize", String.valueOf(pointSize), 
                        "-gravity", "south", 
                        "-annotate", "+0+0+5+5", label,
                        "-crop", (width + 40) +"x" + (height + bottomBorderHeight + 20) + "+0+0", outputJpg.getAbsolutePath()).start();
            baos = new ByteArrayOutputStream();
            new Thread(new OutputDrainerThread(p.getInputStream(), baos)).start();
            new Thread(new OutputDrainerThread(p.getErrorStream(), baos)).start();
            returnCode = p.waitFor();
            if (returnCode != 0) {
                throw new RuntimeException("Invalid return code for process!");
            }
        } else {
            throw new RuntimeException("Unable to parse output: \"" + baos.toString("UTF-8") + "\"");
        }
    }
}
