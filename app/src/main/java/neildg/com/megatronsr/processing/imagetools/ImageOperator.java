package neildg.com.megatronsr.processing.imagetools;

import android.util.Log;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.List;

import neildg.com.megatronsr.constants.ParameterConfig;
import neildg.com.megatronsr.io.ImageFileAttribute;
import neildg.com.megatronsr.io.ImageWriter;
import neildg.com.megatronsr.metrics.ImageMetrics;
import neildg.com.megatronsr.model.single_gaussian.LoadedImagePatch;

/**
 * Miscellaneous image operators
 * Created by NeilDG on 5/23/2016.
 */
public class ImageOperator {
    private final static String TAG = "ImageOperator";

    public static Mat produceMask(Mat inputMat) {
        Mat baseMaskMat = new Mat();
        inputMat.copyTo(baseMaskMat);

        if(inputMat.channels() == 3 || inputMat.channels() == 4) {
            Imgproc.cvtColor(baseMaskMat, baseMaskMat, Imgproc.COLOR_BGR2GRAY);
        }

        baseMaskMat.convertTo(baseMaskMat, CvType.CV_8UC1);
        Imgproc.threshold(baseMaskMat, baseMaskMat, 1, 1, Imgproc.THRESH_BINARY);

        return baseMaskMat;
    }

    public static Mat blendImages(List<Mat> matList) {
        Mat matInput = matList.get(0);
        Mat mergedMat = new Mat(matInput.size(), matInput.type(), new Scalar(0));
        //Add each image from a vector<Mat> inputImages with weight 1.0/n where n is number of images to merge
        for (int i = 0; i < matList.size(); i++) {
            Mat mat = matList.get(i);
            //Core.addWeighted(mergedMat, 1, mat, 1.0/matList.size(), 0, mergedMat);

            Core.add(mergedMat, mat, mergedMat);
            ImageWriter.getInstance().saveMatrixToImage(mergedMat, "fusion", "fuse_"+i, ImageFileAttribute.FileType.JPEG);
        }

        return mergedMat;
    }

    /*
     * Performs zero-filling upsample of a given mat
     */
    public static Mat performZeroFill(Mat fromMat, int scaling, int xOffset, int yOffset) {
        Mat hrMat = Mat.zeros(fromMat.rows() * scaling, fromMat.cols() * scaling, fromMat.type());

        for(int row = 0; row < fromMat.rows(); row++) {
            for(int col = 0; col < fromMat.cols(); col++) {
                double[] lrPixelData = fromMat.get(row, col);

                int resultRow = (row * scaling) + yOffset;
                int resultCol = (col * scaling) + xOffset;

                if(resultRow < hrMat.rows() && resultCol < hrMat.cols()) {
                    hrMat.put(resultRow, resultCol, lrPixelData);
                }
            }
        }

        return hrMat;
    }

    /*
     * PErforms zero-filling according to pixel displacement provided
     */
    public static Mat performZeroFill(Mat fromMat, int scaling, Mat xDisplacement, Mat yDisplacement) {
        Mat hrMat = Mat.zeros(fromMat.rows() * scaling, fromMat.cols() * scaling, fromMat.type());

        for(int row = 0; row < fromMat.rows(); row++) {
            for (int col = 0; col < fromMat.cols(); col++) {
                double[] lrPixelData = fromMat.get(row, col);

                double xOffset = xDisplacement.get(row, col)[0];
                double yOffset = yDisplacement.get(row, col)[0];

                int floorRow = (int) Math.round(yOffset) * scaling;
                int floorCol = (int) Math.round(xOffset) * scaling;

                if(floorRow < hrMat.rows() && floorCol < hrMat.cols()) {
                    //Log.d(TAG, "Debug values. xOffset: " +xOffset+ " yOffset: " +yOffset+ " X: " +floorCol+ " Y: " +floorRow);
                    hrMat.put(floorRow, floorCol, lrPixelData);
                }
            }
        }

        return hrMat;
    }

    /*
     * Copies the rows of a given mat to the hr mat by zero-filling.
     */
    public static void copyMat(Mat fromMat, Mat hrMat, int scaling, int xOffset, int yOffset) {

        for (int row = 0; row < fromMat.rows(); row++) {
            for (int col = 0; col < fromMat.cols(); col++) {
                double[] lrPixelData = fromMat.get(row, col);

                int resultRow = (row * scaling) + yOffset;
                int resultCol = (col * scaling) + xOffset;

                if (resultRow < hrMat.rows() && resultCol < hrMat.cols()) {
                    hrMat.put(resultRow, resultCol, lrPixelData);
                }

            }
        }
    }

    /*
     * Performs interpolation using an existing interpolation algo by OPENCV
     */
    public static Mat performInterpolation(Mat fromMat, int scaling, int interpolationType) {
        Mat hrMat = Mat.zeros(fromMat.rows() * scaling, fromMat.cols() * scaling, fromMat.type());

        Imgproc.resize(fromMat, hrMat, hrMat.size(), scaling, scaling, interpolationType);

        return hrMat;
    }

    public static void replacePatchOnROI(Mat sourceMat, int boundary, LoadedImagePatch sourcePatch, LoadedImagePatch replacementPatch) {

        if(sourcePatch.getColStart() >= 0 && sourcePatch.getColEnd() < sourceMat.cols() && sourcePatch.getRowStart() >= 0 && sourcePatch.getRowEnd() < sourceMat.rows()) {
            Mat subMat = sourceMat.submat(sourcePatch.getRowStart(),sourcePatch.getRowEnd(), sourcePatch.getColStart(), sourcePatch.getColEnd());
            replacementPatch.getPatchMat().copyTo(subMat);

            //attempt to perform blurring by extracting a parent mat at the borders of the submat
            int rowStart = sourcePatch.getRowStart() - boundary;
            int rowEnd = sourcePatch.getRowEnd() + boundary;
            int colStart = sourcePatch.getColStart() - boundary;
            int colEnd = sourcePatch.getColEnd() + boundary;

            if(colStart >= 0 && colEnd < sourceMat.cols() && rowStart >= 0 && rowEnd < sourceMat.rows()) {
                Mat parentMat = sourceMat.submat(rowStart, rowEnd, colStart, colEnd);
                Mat blurMat = new Mat();
                Imgproc.blur(parentMat, blurMat, new Size(3,3));
                Core.addWeighted(parentMat, 1.5 , blurMat, -0.5, 0, parentMat);
                blurMat.release();
            }

        }
    }
}
