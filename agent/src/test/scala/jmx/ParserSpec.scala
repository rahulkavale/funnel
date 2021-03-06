//: ----------------------------------------------------------------------------
//: Copyright (C) 2015 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package funnel
package agent
package jmx

import scalaz.\/, \/._
import scalaz.syntax.std.either._
import org.scalatest._

class ParserSpec extends FlatSpec with Matchers {
  it should "parse zookeeper bean names" in {
    Parser.fromCanonicalName("org.apache.ZooKeeperService:name0=StandaloneServer_port-1,name1=InMemoryDataTree"
      ) should equal (right("org/apache/zookeeperservice/inmemorydatatree/standaloneserver_port-1"))
  }

  it should "parse cassandra bean names" in {
    Parser.fromCanonicalName("org.apache.cassandra.db:columnfamily=schema_columns,keyspace=system,type=ColumnFamilies"
      ) should equal (right("org/apache/cassandra/db/columnfamilies/system/schema_columns"))
  }

  it should "parse the janky jmx mbean names from kafka" in {
    Parser.fromCanonicalName("\"kafka.server\":type=\"BrokerTopicMetrics\",name=\"AllTopicsBytesInPerSec\""
      ) should equal (right("kafka/server/alltopicsbytesinpersec/brokertopicmetrics"))
  }

}
