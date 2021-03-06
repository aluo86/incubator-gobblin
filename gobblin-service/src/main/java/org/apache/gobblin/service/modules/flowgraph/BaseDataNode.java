/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.service.modules.flowgraph;

import com.google.common.base.Preconditions;
import com.typesafe.config.Config;

import org.apache.gobblin.annotation.Alpha;
import org.apache.gobblin.service.modules.dataset.DatasetDescriptor;
import org.apache.gobblin.util.ConfigUtils;

import joptsimple.internal.Strings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * An implementation of {@link DataNode}.
 */
@Alpha
@Slf4j
public class BaseDataNode implements DataNode {
  @Getter
  private String id;
  @Getter
  private Config props;
  @Getter
  private boolean active = true;

  public BaseDataNode(Config nodeProps) throws DataNodeCreationException {
    try {
      String nodeId = ConfigUtils.getString(nodeProps, FlowGraphConfigurationKeys.DATA_NODE_ID_KEY, "");
      Preconditions.checkArgument(!Strings.isNullOrEmpty(nodeId), "Node Id cannot be null or empty");
      this.id = nodeId;
      if (nodeProps.hasPath(FlowGraphConfigurationKeys.DATA_NODE_IS_ACTIVE_KEY)) {
        this.active = nodeProps.getBoolean(FlowGraphConfigurationKeys.DATA_NODE_IS_ACTIVE_KEY);
      }
      this.props = nodeProps;
    } catch(Exception e) {
      throw new DataNodeCreationException(e);
    }
  }

  /**
   * The comparison between two nodes should involve the configuration.
   * Node name is the identifier for the node.
   * */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    BaseDataNode that = (BaseDataNode) o;

    return id.equals(that.getId());
  }

  @Override
  public int hashCode() {
    return this.id.hashCode();
  }
}
