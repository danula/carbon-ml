/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.ml.core.spark.transformations;

import org.apache.spark.api.java.function.Function;
import org.apache.spark.mllib.linalg.Vectors;
import org.apache.spark.mllib.regression.LabeledPoint;

/**
 * This class transforms double array of tokens to labeled point
 */
public class DoubleArrayToLabeledPoint implements Function<double[], LabeledPoint> {

    private static final long serialVersionUID = -3847503088002249546L;

    private DoubleArrayToLabeledPoint() {
    }

    /**
     * Function to transform double array into labeled point
     *
     * @param tokens Double array of tokens
     * @return Labeled point
     */
    @Override
    public LabeledPoint call(double[] tokens) {
        // last index is the response value after the upstream transformations
        double response = tokens[tokens.length - 1];
        // new feature vector does not contain response variable value
        double[] features = new double[tokens.length - 1];
        for (int i = 0; i < tokens.length - 1; i++) {
            features[i] = tokens[i];
        }
        return new LabeledPoint(response, Vectors.dense(features));
    }

    public static class Builder {
        public DoubleArrayToLabeledPoint build() {
            return new DoubleArrayToLabeledPoint();
        }
    }
}
