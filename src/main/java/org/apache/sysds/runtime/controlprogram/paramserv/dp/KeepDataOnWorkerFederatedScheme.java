/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.runtime.controlprogram.paramserv.dp;

import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysds.runtime.controlprogram.federated.FederatedData;
import org.apache.sysds.runtime.controlprogram.federated.FederatedRange;
import org.apache.sysds.runtime.controlprogram.federated.FederationMap;
import org.apache.sysds.runtime.meta.DataCharacteristics;
import org.apache.sysds.runtime.meta.MatrixCharacteristics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class KeepDataOnWorkerFederatedScheme extends DataPartitionFederatedScheme {
	@Override
	public Result doPartitioning(MatrixObject features, MatrixObject labels) {
		if (features.isFederated(FederationMap.FType.ROW)
				|| labels.isFederated(FederationMap.FType.ROW)) {

			// partition features
			List<MatrixObject> pFeatures = Collections.synchronizedList(new ArrayList<>());
			features.getFedMapping().forEachParallel((range, data) -> {
				// TODO: This slicing is really ugly, rework
				MatrixObject slice = new MatrixObject(features);
				slice.updateDataCharacteristics(new MatrixCharacteristics(range.getSize(0), range.getSize(1)));

				HashMap<FederatedRange, FederatedData> newFedHashMap = new HashMap<>();
				newFedHashMap.put(range, data);
				slice.setFedMapping(new FederationMap(newFedHashMap));
				slice.getFedMapping().setType(FederationMap.FType.ROW);

				pFeatures.add(slice);
				return null;
			});

			// partition labels
			List<MatrixObject> pLabels = Collections.synchronizedList(new ArrayList<>());
			labels.getFedMapping().forEachParallel((range, data) -> {
				// TODO: This slicing is really ugly, rework
				MatrixObject slice = new MatrixObject(labels);
				slice.updateDataCharacteristics(new MatrixCharacteristics(range.getSize(0), range.getSize(1)));

				HashMap<FederatedRange, FederatedData> newFedHashMap = new HashMap<>();
				newFedHashMap.put(range, data);
				slice.setFedMapping(new FederationMap(newFedHashMap));
				slice.getFedMapping().setType(FederationMap.FType.ROW);

				pLabels.add(slice);
				return null;
			});

			return new Result(pFeatures, pLabels);
		}
		else {
			throw new DMLRuntimeException(String.format("Paramserv func: " +
					"currently only supports row federated data"));
		}
	}
}
