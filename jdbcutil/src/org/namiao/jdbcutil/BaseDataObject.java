/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.namiao.jdbcutil;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Value对象的基类，当使用<code>DaoTemplate</code>时，Value对象继承这个类，多表关联查询时，
 * 其他表的字段会存入props（Map）中。
 * 
 * @author <a href="mailto:areyouok@gmail.com">huangli</a>
 * @see <code>DaoTemplate</code>
 */
public abstract class BaseDataObject implements Serializable {

	private static final long serialVersionUID = -5075630072275200425L;
	
	protected Map<String, Object> props;
	
    public BaseDataObject(){
	    props = new HashMap<String, Object>();
	}

	@Transient
	public Map<String, Object> getProps() {
		return props;
	}

	public void setProps(Map<String, Object> props) {
		this.props = props;
	}
}
