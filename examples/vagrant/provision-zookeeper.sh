#!/bin/bash -x
#
# Copyright 2014 Apache Software Foundation
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

apt-get update
apt-get -y install zookeeper
echo "JVMFLAGS=\"-Djava.net.preferIPv4Stack=true\"" >> /etc/zookeeper/conf/environment
cat > /etc/rc.local <<EOF
#!/bin/sh -e
/usr/share/zookeeper/bin/zkServer.sh start
EOF
chmod +x /etc/rc.local
/etc/rc.local
