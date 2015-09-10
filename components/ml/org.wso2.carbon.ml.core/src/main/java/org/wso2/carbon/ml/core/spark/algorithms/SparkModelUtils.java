/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.ml.core.spark.algorithms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.api.java.JavaDoubleRDD;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.evaluation.BinaryClassificationMetrics;
import org.apache.spark.mllib.regression.LabeledPoint;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.ml.commons.constants.MLConstants;
import org.wso2.carbon.ml.commons.domain.Feature;
import org.wso2.carbon.ml.commons.domain.FeatureType;
import org.wso2.carbon.ml.core.internal.MLModelConfigurationContext;
import org.wso2.carbon.ml.core.spark.summary.ClassClassificationAndRegressionModelSummary;
import org.wso2.carbon.ml.core.spark.summary.TestResultDataPoint;
import org.wso2.carbon.ml.core.spark.summary.PredictedVsActual;
import org.wso2.carbon.ml.core.spark.summary.ProbabilisticClassificationModelSummary;
import org.wso2.carbon.ml.core.utils.MLCoreServiceValueHolder;
import scala.Tuple2;

import java.text.DecimalFormat;
import java.util.*;

public class SparkModelUtils {
    private static final Log log = LogFactory.getLog(SparkModelUtils.class);

    /**
     * Private constructor to prevent any other class from instantiating.
     */
    private SparkModelUtils() {
    }

    /**
     * A utility method to generate probabilistic classification model summary
     *
     * @param scoresAndLabels   Tuple2 containing scores and labels
     * @return                  Probabilistic classification model summary
     */
    public static ProbabilisticClassificationModelSummary generateProbabilisticClassificationModelSummary(JavaSparkContext sparkContext,
                                                                                                          JavaRDD<LabeledPoint> testingData,
                                                                                                          JavaRDD<Tuple2<Object, Object>> scoresAndLabels) {
        int sampleSize = MLCoreServiceValueHolder.getInstance().getSummaryStatSettings().getSampleSize();
        ProbabilisticClassificationModelSummary probabilisticClassificationModelSummary =
                new ProbabilisticClassificationModelSummary();
        // store predictions and actuals
        List<PredictedVsActual> predictedVsActuals = new ArrayList<PredictedVsActual>();
        DecimalFormat decimalFormat = new DecimalFormat(MLConstants.DECIMAL_FORMAT);
        for (Tuple2<Object, Object> scoreAndLabel : scoresAndLabels.take(sampleSize)) {
            PredictedVsActual predictedVsActual = new PredictedVsActual();
            predictedVsActual.setPredicted(Double.parseDouble(decimalFormat.format(scoreAndLabel._1())));
            predictedVsActual.setActual(Double.parseDouble(decimalFormat.format(scoreAndLabel._2())));
            predictedVsActuals.add(predictedVsActual);
            if (log.isTraceEnabled()) {
                log.trace("Predicted: "+predictedVsActual.getPredicted() + " ------ Actual: "+predictedVsActual.getActual());
            }
        }
        // create a list of feature values
        List<double[]> features = new ArrayList<double[]>();
        for (LabeledPoint labeledPoint : testingData.take(sampleSize)) {
            if(labeledPoint != null && labeledPoint.features() != null) {
                double[] rowFeatures = labeledPoint.features().toArray();
                features.add(rowFeatures);
            }
        }
        // create a list of feature values with predicted vs. actuals
        List<TestResultDataPoint> testResultDataPoints = new ArrayList<TestResultDataPoint>();
        for(int i = 0; i < features.size(); i++) {
            TestResultDataPoint testResultDataPoint = new TestResultDataPoint();
            testResultDataPoint.setPredictedVsActual(predictedVsActuals.get(i));
            testResultDataPoint.setFeatureValues(features.get(i));
            testResultDataPoints.add(testResultDataPoint);
        }
        // covert List to JavaRDD
        JavaRDD<TestResultDataPoint> testResultDataPointsJavaRDD = sparkContext.parallelize(testResultDataPoints);
        // collect RDD as a sampled list
        List<TestResultDataPoint> testResultDataPointsSample;
        if(testResultDataPointsJavaRDD.count() > sampleSize) {
            testResultDataPointsSample = testResultDataPointsJavaRDD.takeSample(true, sampleSize);
        }
        else {
            testResultDataPointsSample = testResultDataPointsJavaRDD.collect();
        }
        probabilisticClassificationModelSummary.setTestResultDataPointsSample(testResultDataPointsSample);
        // generate binary classification metrics
        BinaryClassificationMetrics metrics = new BinaryClassificationMetrics(JavaRDD.toRDD(scoresAndLabels));
        // store AUC
        probabilisticClassificationModelSummary.setAuc(metrics.areaUnderROC());
        // store ROC data points
        List<Tuple2<Object, Object>> rocData = metrics.roc().toJavaRDD().collect();
        JSONArray rocPoints = new JSONArray();
        for (int i = 0; i < rocData.size(); i += 1) {
            JSONArray point = new JSONArray();
            point.put(decimalFormat.format(rocData.get(i)._1()));
            point.put(decimalFormat.format(rocData.get(i)._2()));
            rocPoints.put(point);
        }
        probabilisticClassificationModelSummary.setRoc(rocPoints.toString());
        return probabilisticClassificationModelSummary;
    }

    /**
     * A utility method to generate regression model summary
     *
     * @param predictionsAndLabels  Tuple2 containing predicted and actual values
     * @return                      Regression model summary
     */
    public static ClassClassificationAndRegressionModelSummary generateRegressionModelSummary(JavaSparkContext sparkContext,
                                                                                              JavaRDD<LabeledPoint> testingData,
                                                                                              JavaRDD<Tuple2<Double, Double>> predictionsAndLabels) {
        int sampleSize = MLCoreServiceValueHolder.getInstance().getSummaryStatSettings().getSampleSize();
        ClassClassificationAndRegressionModelSummary regressionModelSummary =
                new ClassClassificationAndRegressionModelSummary();
        // store predictions and actuals
        List<PredictedVsActual> predictedVsActuals = new ArrayList<PredictedVsActual>();
        DecimalFormat decimalFormat = new DecimalFormat(MLConstants.DECIMAL_FORMAT);
        for (Tuple2<Double, Double> scoreAndLabel : predictionsAndLabels.take(sampleSize)) {
            PredictedVsActual predictedVsActual = new PredictedVsActual();
            predictedVsActual.setPredicted(Double.parseDouble(decimalFormat.format(scoreAndLabel._1())));
            predictedVsActual.setActual(Double.parseDouble(decimalFormat.format(scoreAndLabel._2())));
            predictedVsActuals.add(predictedVsActual);
        }
        // create a list of feature values
        List<double[]> features = new ArrayList<double[]>();
        for (LabeledPoint labeledPoint : testingData.take(sampleSize)) {
            if(labeledPoint != null && labeledPoint.features() != null) {
                double[] rowFeatures = labeledPoint.features().toArray();
                features.add(rowFeatures);
            }
        }
        // create a list of feature values with predicted vs. actuals
        List<TestResultDataPoint> testResultDataPoints = new ArrayList<TestResultDataPoint>();
        for(int i = 0; i < features.size(); i++) {
            TestResultDataPoint testResultDataPoint = new TestResultDataPoint();
            testResultDataPoint.setPredictedVsActual(predictedVsActuals.get(i));
            testResultDataPoint.setFeatureValues(features.get(i));
            testResultDataPoints.add(testResultDataPoint);
        }
        // covert List to JavaRDD
        JavaRDD<TestResultDataPoint> testResultDataPointsJavaRDD = sparkContext.parallelize(testResultDataPoints);
        // collect RDD as a sampled list
        List<TestResultDataPoint> testResultDataPointsSample;
        if(testResultDataPointsJavaRDD.count() > sampleSize) {
            testResultDataPointsSample = testResultDataPointsJavaRDD.takeSample(true, sampleSize);
        }
        else {
            testResultDataPointsSample = testResultDataPointsJavaRDD.collect();
        }
        regressionModelSummary.setTestResultDataPointsSample(testResultDataPointsSample);
        // calculate mean squared error (MSE)
        double meanSquaredError = new JavaDoubleRDD(predictionsAndLabels.map(
                new Function<Tuple2<Double, Double>, Object>() {
                    private static final long serialVersionUID = -162193633199074816L;

                    public Object call(Tuple2<Double, Double> pair) {
                        return Math.pow(pair._1() - pair._2(), 2.0);
                    }
                }
        ).rdd()).mean();
        regressionModelSummary.setError(meanSquaredError);
        return regressionModelSummary;
    }

    /**
     * A utility method to generate class classification model summary
     *
     * @param predictionsAndLabels Predictions and actual labels
     * @return Class classification model summary
     */
    public static ClassClassificationAndRegressionModelSummary getClassClassificationModelSummary(JavaSparkContext sparkContext,
                                                                                                  JavaRDD<LabeledPoint> testingData,
                                                                                                  JavaPairRDD<Double, Double> predictionsAndLabels) {
        int sampleSize = MLCoreServiceValueHolder.getInstance().getSummaryStatSettings().getSampleSize();
        ClassClassificationAndRegressionModelSummary classClassificationModelSummary = new
                ClassClassificationAndRegressionModelSummary();
        // store predictions and actuals
        List<PredictedVsActual> predictedVsActuals = new ArrayList<PredictedVsActual>();
        for (Tuple2<Double, Double> scoreAndLabel : predictionsAndLabels.take(sampleSize)) {
            PredictedVsActual predictedVsActual = new PredictedVsActual();
            predictedVsActual.setPredicted(scoreAndLabel._1());
            predictedVsActual.setActual(scoreAndLabel._2());
            predictedVsActuals.add(predictedVsActual);
        }
        // create a list of feature values
        List<double[]> features = new ArrayList<double[]>();
        for (LabeledPoint labeledPoint : testingData.take(sampleSize)) {
            if(labeledPoint != null && labeledPoint.features() != null) {
                double[] rowFeatures = labeledPoint.features().toArray();
                features.add(rowFeatures);
            }
        }
        // create a list of feature values with predicted vs. actuals
        List<TestResultDataPoint> testResultDataPoints = new ArrayList<TestResultDataPoint>();
        for(int i = 0; i < features.size(); i++) {
            TestResultDataPoint testResultDataPoint = new TestResultDataPoint();
            testResultDataPoint.setPredictedVsActual(predictedVsActuals.get(i));
            testResultDataPoint.setFeatureValues(features.get(i));
            testResultDataPoints.add(testResultDataPoint);
        }
        // covert List to JavaRDD
        JavaRDD<TestResultDataPoint> testResultDataPointsJavaRDD = sparkContext.parallelize(testResultDataPoints);
        // collect RDD as a sampled list
        List<TestResultDataPoint> testResultDataPointsSample;
        if(testResultDataPointsJavaRDD.count() > sampleSize) {
            testResultDataPointsSample = testResultDataPointsJavaRDD.takeSample(true, sampleSize);
        }
        else {
            testResultDataPointsSample = testResultDataPointsJavaRDD.collect();
        }
        classClassificationModelSummary.setTestResultDataPointsSample(testResultDataPointsSample);
        // calculate test error
        double error = 1.0 * predictionsAndLabels.filter(new Function<Tuple2<Double, Double>, Boolean>() {
            private static final long serialVersionUID = -3063364114286182333L;

            @Override
            public Boolean call(Tuple2<Double, Double> pl) {
                return !pl._1().equals(pl._2());
            }
        }).count() / predictionsAndLabels.count();
        classClassificationModelSummary.setError(error);
        return classClassificationModelSummary;
    }
    
    /**
     * Build the encodings against each categorical feature.
     * @return a list of encodings - last value of the list represent the encodings for the response variable.
     * index - index of the feature
     * value - Map<String, Integer> - map of unique values of this feature and there encoded values.
     *      key - unique value
     *      value - encoded value
     * 
     */
    public static List<Map<String, Integer>> buildEncodings(MLModelConfigurationContext ctx) {
        List<Feature> features = ctx.getFacts().getFeatures();
        Map<String, String> summaryStats = ctx.getSummaryStatsOfFeatures();
        List<Integer> newToOldIndicesList = ctx.getNewToOldIndicesList();
        int responseIndex = ctx.getResponseIndex();
        List<Map<String, Integer>> encodings = new ArrayList<Map<String, Integer>>();
        for (int i = 0; i < newToOldIndicesList.size()+1; i++) {
            encodings.add(new HashMap<String, Integer>());
        }
        for (Feature feature : features) {
            Map<String, Integer> encodingMap = new HashMap<String, Integer>();
            if (feature.getType().equals(FeatureType.CATEGORICAL)) {
                List<String> uniqueVals = getUniqueValues(summaryStats.get(feature.getName()));
                Collections.sort(uniqueVals);
                for (int i = 0; i < uniqueVals.size(); i++) {
                    encodingMap.put(uniqueVals.get(i), i);
                }
                int newIndex = newToOldIndicesList.indexOf(feature.getIndex());
                if (newIndex != -1) {
                    encodings.set(newIndex, encodingMap);
                } else if (feature.getIndex() == responseIndex) {
                    // response encoding at the end
                    encodings.set(encodings.size()-1, encodingMap);
                }
            }
        }

        return encodings;
    }
    
    private static List<String> getUniqueValues(String statsAsJson) {
        List<String> uniqueValues = new ArrayList<String>();
        /*
         * "values": [
            [
                "0.0",
                147
            ],
            [
                "1.0",
                33
            ]
        ],
         */
        if (statsAsJson == null) {
            return uniqueValues;
        }
        try {
            //new JSONArray(statsAsJson).getJSONObject(0).getJSONArray("values").getJSONArray(0).getString(0)
            JSONArray array = new JSONArray(statsAsJson);
            JSONObject jsonObj = array.getJSONObject(0);
            JSONArray array1 = jsonObj.getJSONArray("values");
            if (array1 == null) {
                return uniqueValues;
            }
            for (int i = 0; i < array1.length(); i++) {
                JSONArray array2 = array1.getJSONArray(i);
                if (array2 != null) {
                    uniqueValues.add(array2.getString(0));
                }
            }
        } catch (JSONException e) {
            log.warn("Failed to extract unique values from summary stats: "+statsAsJson, e);
            return uniqueValues;
        }
        
        return uniqueValues;
    }
    
    public static double getMean(String statsAsJson) {
        if (statsAsJson == null) {
            return 0.0;
        }
        try {
            // new JSONArray(statsAsJson).getJSONObject(0).getJSONArray("values").getJSONArray(0).getString(0)
            JSONArray array = new JSONArray(statsAsJson);
            JSONObject jsonObj = array.getJSONObject(0);
            String mean = jsonObj.getString("mean");
            if (mean == null) {
                return 0.0;
            } else {
                try {
                    return Double.parseDouble(mean);
                } catch (NumberFormatException e) {
                    return 0.0;
                }
            }

        } catch (JSONException e) {
            log.warn("Failed to extract unique values from summary stats: " + statsAsJson, e);
            return 0.0;
        }

    }
}
