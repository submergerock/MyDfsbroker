#!/usr/bin/env bash
#
# Copyright (C) 2007-2012 Hypertable, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# The installation directory
export HYPERTABLE_HOME=$(cd `dirname "$0"`/.. && pwd)
. $HYPERTABLE_HOME/bin/ht-env.sh

if [ ! -f $HYPERTABLE_HOME/bin/ThriftBroker ]; then
  echo "ThriftBroker not installed";
  exit 0; # OK, as it's optional
fi


usage() {
  echo ""
  echo "usage: start-thriftbroker.sh [OPTIONS] [<global-args>]"
  echo ""
  echo "OPTIONS:"
  echo "  --valgrind  run thriftbroker with valgrind"
  echo ""
}

PIDBASE="ThriftBroker"

while [ "$1" != "${1##[-+]}" ]; do
  case $1 in
    --valgrind)
      VALGRIND="valgrind -v --log-file=vg.tb.%p --leak-check=full --num-callers=20 "
      shift;;
    --pidbase)
      shift
      PIDBASE=$1
      shift;;
    *)
      break;;
  esac
done

start_server thriftbroker ThriftBroker $PIDBASE "$@"
