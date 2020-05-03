/* BSD 2-Clause (c) 2014: A.Doherty (Oxford), Shing Chan (Oxford)
 *
 * Code for extracting basic summary stats from raw triaxial 
 * acceleration measurements.
 */
import java.util.Arrays;
import java.util.List;

public class AccStats {

    public static double [] getAccStats(
            double[] xArray,
            double[] yArray,
            double[] zArray,
            Filter filter,
            Boolean getFeatures,
            int sampleRate,
            int numFFTbins)
    {
        // calculate raw x/y/z summary values
        double xMean = mean(xArray);
        double yMean = mean(yArray);
        double zMean = mean(zArray);
        double xRange = range(xArray);
        double yRange = range(yArray);
        double zRange = range(zArray);
        double xStd = std(xArray, xMean);
        double yStd = std(yArray, yMean);
        double zStd = std(zArray, zMean);
        double xyCovariance = covariance(xArray, yArray, xMean, yMean, 0);
        double xzCovariance = covariance(xArray, zArray, xMean, zMean, 0);
        double yzCovariance = covariance(yArray, zArray, yMean, zMean, 0);

        // calculate vector magnitude (i.e. euclidean norm minus one) stats
        double[] enmo = getENMO(xArray, yArray, zArray);
        // filter signal
        if (filter != null){
            filter.filter(enmo);
        }
        double[] enmoTrunc = trunc(enmo);
        double[] enmoAbs = abs(enmo);
        

        // don't forget to change header method immediately below !!!
        double[] basicStatistics = {
            mean(enmoTrunc), mean(enmoAbs),
            xMean, yMean, zMean,
            xRange, yRange, zRange,
            xStd, yStd, zStd,
            xyCovariance, xzCovariance, yzCovariance,
        };
        
        double[] outputFeats = null;
        //extract features if requested
        if (getFeatures){
            double[] features = Features.getFeatures(xArray, yArray, zArray, 
                                            enmoTrunc, sampleRate, numFFTbins);
            outputFeats = AccStats.combineArrays(basicStatistics, features);
        } else{
            outputFeats = basicStatistics;
        }
        
        return outputFeats;
    }

    public static String getStatsHeader(Boolean getFeatures, int numFFTbins){
        String header = "enmoTrunc,enmoAbs";
        header += ",xMean,yMean,zMean";
        header += ",xRange,yRange,zRange,xStd,yStd,zStd";
        header += ",xyCov,xzCov,yzCov";
        if(getFeatures){
            header += "," + Features.getFeaturesHeader(numFFTbins);
        }
        return header;
    }


    private static double[] getENMO(double[] x, double[] y, double[] z) {
        //get euclidean norm minus one
        double[] enmo = new double[x.length];
        for (int c = 0; c < x.length; c++){
            double vm = getVectorMagnitude(x[c], y[c], z[c]);
            enmo[c] = vm-1;
        }
        return enmo;
    }


    public static double getVectorMagnitude(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }


    private static double[] abs(double[] vals) {
        double[] output = new double[vals.length];
        for (int c = 0; c < vals.length; c++) {
            output[c] = Math.abs(vals[c]);
        }
        return output;
    }


    private static double[] trunc(double[] vals) {
        double[] output = new double[vals.length];
        double tmp;
        for (int c = 0; c < vals.length; c++) {
            tmp = vals[c];
            if (tmp < 0) {
                tmp = 0;
            }
            output[c] = tmp;
        }
        return output;
    }


    /*
     * Implementation of the following features aims to match the paper:
     * Hip and Wrist Accelerometer Algorithms for Free-Living Behavior Classification
     * Katherine Ellis, Jacqueline Kerr, Suneeta Godbole, John Staudenmayer, and Gert Lanckriet
     */

    // percentiles = {0.25, 0.5, 0.75}, to calculate 25th, median and 75th percentile
    public static double[] percentiles(double[] vals, double[] percentiles) {
        double[] output = new double[percentiles.length];
        int n = vals.length;
        if (n == 0) {
            Arrays.fill(output, Double.NaN);
            return output;
        }
        if (n == 1) {
            Arrays.fill(output, vals[0]);
            return output;
        }
        double[] sortedVals = vals.clone();
        Arrays.sort(sortedVals);
        for (int i = 0; i<percentiles.length; i++) {
            // this follows the R default (R7) interpolation model
            // https://en.wikipedia.org/wiki/Quantile#Estimating_quantiles_from_a_sample
            double h = percentiles[i] * (n-1) + 1;
            if (h<=1.0) {
                output[i] = sortedVals[0];
                continue;
            }
            if (h>=n) {
                output[i] = sortedVals[n-1];
                continue;
            }
            // interpolate using: x[h] + (h - floor(h)) (x[h + 1] - x[h])
            int hfloor = (int) Math.floor(h);
            double xh = sortedVals[hfloor-1] ;
            double xh2 = sortedVals[hfloor] ;
            output[i] = xh + (h - hfloor) * (xh2 - xh);
            //S ystem.out.println(percentiles[i] + ", h:" + h + ", " + xh + ", " + xh2);
        }
        return output;
    }


    // returns {mean, standard deviation} together to reduce processing time
    public static double[] angleAvgStd(double[] vals1, double[] vals2) {
        int len = vals1.length;
        if ( len < 2 || len != vals2.length ) {
            return new double[] {Double.NaN, Double.NaN};
        }
        double[] angles = new double[len];
        double total = 0.0;
        for (int c = 0; c < len; c++) {
            angles[c] = Math.atan2(vals1[c],vals2[c]);
            total += angles[c];
        }
        double mean = total/len;
        double var = 0.0;
        for (int c = 0; c < len; c++) {
            var += Math.pow(angles[c] - mean, 2);
        }
        // use R's (n-1) denominator standard deviation (Bessel's correction)
        double std = Math.sqrt(var/(len-1));
        return new double[] {mean, std};
    }


    // covariance of two signals (with lag in samples)
    private static double covariance(double[] vals1, double[] vals2, double mean1, double mean2, int lag) {
        lag = Math.abs(lag); // should be identical
        if ( vals1.length <= lag || vals1.length != vals2.length ) {
            return Double.NaN;
        }
        double cov = 0; // covariance
        for (int c = lag; c < vals1.length; c++) {
            if (!Double.isNaN(vals1[c-lag]) && !Double.isNaN(vals2[c])) {
                cov += (vals1[c]-mean1) * (vals2[c]-mean2);
            }
        }
        cov /= vals1.length+1-lag;
        return cov;
    }


    public static double correlation(double[] vals1, double[] vals2) {
        return correlation(vals1, vals2, 0);
    }

    public static double correlation(double[] vals1, double[] vals2, int lag) {
        lag = Math.abs(lag); // should be identical
        if ( vals1.length <= lag || vals1.length != vals2.length ) {
            return Double.NaN;
        }
        double sx = 0.0;
        double sy = 0.0;
        double sxx = 0.0;
        double syy = 0.0;
        double sxy = 0.0;

        int nmax = vals1.length;
        int n = nmax - lag;

        for(int i = lag; i < nmax; ++i) {
            double x = vals1[i-lag];
            double y = vals2[i];

            sx += x;
            sy += y;
            sxx += x * x;
            syy += y * y;
            sxy += x * y;
        }

        // covariation
        double cov = sxy / n - sx * sy / n / n;
        // standard error of x
        double sigmax = Math.sqrt(sxx / n -  sx * sx / n / n);
        // standard error of y
        double sigmay = Math.sqrt(syy / n -  sy * sy / n / n);

        // correlation is just a normalized covariation
        return cov / sigmax / sigmay;
    }


    public static double sum(double[] vals) {
        if (vals.length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int c = 0; c < vals.length; c++) {
            if (!Double.isNaN(vals[c])) {
                sum += vals[c];
            }
        }
        return sum;
    }


    private static double sum(List<Double> vals) {
        if (vals.size() == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int c = 0; c < vals.size(); c++) {
            sum += vals.get(c);
        }
        return sum;
    }


    public static double mean(double[] vals) {
        if (vals.length == 0) {
            return Double.NaN;
        }
        return sum(vals) / (double) vals.length;
    }


    public static double mean(List<Double> vals) {
        if (vals.size() == 0) {
            return Double.NaN;
        }
        return sum(vals) / (double) vals.size();
    }


    public static double range(double[] vals) {
        if (vals.length == 0) {
            return Double.NaN;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int c = 0; c < vals.length; c++) {
            if (vals[c] < min) {
                min = vals[c];
            }
            if (vals[c] > max) {
                max = vals[c];
            }
        }
        return max - min;
    }


    // standard deviation
    public static double std(double[] vals, double mean) {
        if (vals.length == 0) {
            return Double.NaN;
        }
        double var = 0; // variance
        double len = vals.length; // length
        for (int c = 0; c < len; c++) {
            if (!Double.isNaN(vals[c])) {
                var += Math.pow((vals[c] - mean), 2);
            }
        }
        return Math.sqrt(var / len);
    }


    // same as above but matches R's (n-1) denominator (Bessel's correction)
    public static double stdR(double[] vals, double mean) {
        if (vals.length == 0) {
            return Double.NaN;
        }
        double var = 0; // variance
        double len = vals.length; // length
        for (int c = 0; c < len; c++) {
            if (!Double.isNaN(vals[c])) {
                var += Math.pow((vals[c] - mean), 2);
            }
        }
        return Math.sqrt(var / (len-1));
    }


    public static int countStuckVals(
            double[] xArray,
            double[] yArray,
            double[] zArray) {
        //get necessary background stats...
        double xMean = mean(xArray);
        double yMean = mean(yArray);
        double zMean = mean(zArray);
        double xStd = std(xArray, xMean);
        double yStd = std(yArray, yMean);
        double zStd = std(zArray, zMean);
        // see if values are likely to have been abnormally stuck during this epoch
        int numStuckValues = 0;
        double stuckVal = 1.5;
        if (xStd == 0 && (xMean < -stuckVal || xMean > stuckVal)) {
            numStuckValues += 1;
        }
        if (yStd == 0 && (yMean < -stuckVal || yMean > stuckVal)) {
            numStuckValues += 1;
        }
        if (zStd == 0 && (zMean < -stuckVal || zMean > stuckVal)) {
            numStuckValues += 1;
        }
        return numStuckValues;
    }


    public static double[] combineArrays(double[] array1, double[] array2){
        double[] output = new double[array1.length + array2.length];
        for(int c=0; c<array1.length; c++){
            output[c] = array1[c];
        }
        for(int c=0; c<array2.length; c++){
            output[array1.length + c] = array2[c];
        }
        return output;
    }
   

}