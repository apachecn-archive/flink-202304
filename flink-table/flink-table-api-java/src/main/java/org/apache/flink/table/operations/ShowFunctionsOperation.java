/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.operations;

import org.apache.flink.table.api.internal.TableResultInternal;

import java.util.Arrays;

import static org.apache.flink.table.api.internal.TableResultUtils.buildStringArrayResult;

/** Operation to describe a SHOW [USER] FUNCTIONS statement. */
public class ShowFunctionsOperation implements ShowOperation {

    /**
     * Represent scope of function.
     *
     * <ul>
     *   <li><b>USER</b> return only user-defined functions
     *   <li><b>ALL</b> return all user-defined and built-in functions
     * </ul>
     */
    public enum FunctionScope {
        USER,
        ALL
    }

    private final FunctionScope functionScope;

    public ShowFunctionsOperation() {
        // "SHOW FUNCTIONS" default is ALL scope
        this.functionScope = FunctionScope.ALL;
    }

    public ShowFunctionsOperation(FunctionScope functionScope) {
        this.functionScope = functionScope;
    }

    @Override
    public String asSummaryString() {
        if (functionScope == FunctionScope.ALL) {
            return "SHOW FUNCTIONS";
        } else {
            return String.format("SHOW %s FUNCTIONS", functionScope);
        }
    }

    public FunctionScope getFunctionScope() {
        return functionScope;
    }

    @Override
    public TableResultInternal execute(Context ctx) {
        final String[] functionNames;
        switch (functionScope) {
            case USER:
                functionNames = ctx.getFunctionCatalog().getUserDefinedFunctions();
                break;
            case ALL:
                functionNames = ctx.getFunctionCatalog().getFunctions();
                break;
            default:
                throw new UnsupportedOperationException(
                        String.format(
                                "SHOW FUNCTIONS with %s scope is not supported.", functionScope));
        }
        Arrays.sort(functionNames);
        return buildStringArrayResult("function name", functionNames);
    }
}
