#!/bin/bash
if [ ! -z $AWS_ACCESS_KEY ] ; then
  export AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY
  export AWS_SECRET_ACCESS_KEY=$AWS_SECRET_KEY
fi

#export USER_ID=$(id -u)
#export GROUP_ID=$(id -g)
#sed '/^omar/d' /etc/passwd > /tmp/passwd
#echo omar:x:$USER_ID:$GROUP_ID:Default Application User:$HOME:/sbin/nologin >> /tmp/passwd

#export LD_PRELOAD=/usr/lib64/libnss_wrapper.so
#export NSS_WRAPPER_PASSWD=/tmp/passwd
#export NSS_WRAPPER_GROUP=/etc/group
if [ -z $MOUNT_POINT ] ; then
  export MOUNT_POINT=/s3
fi

# force to forground
#  we are taking a comma separated list of buckets in the form of
#  AWS  <bucket>:<prefix-path>,.....
#  where :<prefix-path> is optional.  
#  we will mount to the location <mount-point>/<prefix-path>
# 
GOOFY_OPTS="-f ${GOOFY_OPTS}"
if [ ! -z "${BUCKETS}" ] ; then
  SPLIT_BUCKET=${BUCKETS//\,/ }
  
  for BUCKET in ${SPLIT_BUCKET} ; do
    BUCKET_PATH="${MOUNT_POINT}/${BUCKET//://}"
    mkdir -p $BUCKET_PATH
    goofys ${GOOFY_OPTS} ${BUCKET} ${BUCKET_PATH} &
  done
fi

if [ "${JAVA_ARGS}" == "" ] ; then
   export JAVA_ARGS="-server -Xms256m -Xmx1024m -XX:+CMSClassUnloadingEnabled -XX:+UseGCOverheadLimit -XX:+UnlockExperimentalVMOptions  -XX:+UseCGroupMemoryLimitForHeap -XX:MaxRAMFraction=1 -Djava.security.egd=file:/dev/./urandom"
fi

if [ "${KEY_STORE}" != "" ] ; then
   if [ "${JAVA_ARGS}" == "" ] ; then
      export JAVA_ARGS="-Djavax.net.ssl.keyStore=${KEY_STORE}"
   else
      export JAVA_ARGS="-Djavax.net.ssl.keyStore=${KEY_STORE} ${JAVA_ARGS}"
   fi
fi 

if [ "${KEY_STORE_PASSWORD}" != "" ] ; then
   if [ "${JAVA_ARGS}" == "" ] ; then
      export JAVA_ARGS="-Djavax.net.ssl.keyStorePassword=${KEY_STORE_PASSWORD}"
   else
      export JAVA_ARGS="-Djavax.net.ssl.keyStorePassword=${KEY_STORE_PASSWORD} ${JAVA_ARGS}"
   fi
fi 

if [ "${TRUST_STORE}" != "" ] ; then
   if [ "${JAVA_ARGS}" == "" ] ; then
      export JAVA_ARGS="-Djavax.net.ssl.trustStore=${TRUST_STORE}"
   else
      export JAVA_ARGS="-Djavax.net.ssl.trustStore=${TRUST_STORE} ${JAVA_ARGS}"
   fi
fi 

if [ "${TRUST_STORE_PASSWORD}" != "" ] ; then
   if [ "${JAVA_ARGS}" == "" ] ; then
      export JAVA_ARGS="-Djavax.net.ssl.trustStorePassword=${TRUST_STORE_PASSWORD}"
   else
      export JAVA_ARGS="-Djavax.net.ssl.trustStorePassword${TRUST_STORE_PASSWORD} ${JAVA_ARGS}"
   fi
fi 
export JAR_FILE=`find ${HOME} -name "*.jar"`
echo "Running command: java ${JAVA_ARGS} -jar ${JAR_FILE}"
java ${JAVA_ARGS} -jar ${JAR_FILE}
