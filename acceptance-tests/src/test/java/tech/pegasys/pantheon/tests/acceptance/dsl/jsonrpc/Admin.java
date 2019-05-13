/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.tests.acceptance.dsl.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.RunnableNode;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.JsonRequestFactories;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;

import java.io.IOException;
import java.net.URI;

import org.web3j.protocol.core.Response;

public class Admin {

    public Condition addPeer(final Node node) {

        System.out.println("999");

        return new Condition() {
            @Override
            public void verify(Node x) {

                System.out.println("888");

                final Boolean result =
                        node.execute(
                                new Transaction<Boolean>() {
                                    @Override
                                    public Boolean execute(JsonRequestFactories requestFactories) {

                                        System.out.println("777");

                                        Response<Boolean> resp = null;
                                        try {
                                            URI enodeUrl = ((RunnableNode) x).enodeUrl();
                                            resp = requestFactories.admin().adminAddPeer(enodeUrl).send();
                                            assertThat(resp).isNotNull();
                                            assertThat(resp.hasError()).isFalse();
                                        } catch (final Exception ignored) {
                                        }
                                        return resp.getResult();
                                    }
                                });
                assertThat(result).isTrue();
            }
        };
    }
}
