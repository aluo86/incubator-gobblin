/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.aws;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Region;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClient;
import com.amazonaws.services.autoscaling.model.BlockDeviceMapping;
import com.amazonaws.services.autoscaling.model.CreateAutoScalingGroupRequest;
import com.amazonaws.services.autoscaling.model.CreateLaunchConfigurationRequest;
import com.amazonaws.services.autoscaling.model.InstanceMonitoring;
import com.amazonaws.services.autoscaling.model.Tag;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.AvailabilityZone;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.CreateKeyPairResult;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;


/**
 * This class is responsible for all AWS API calls
 *
 * @author Abhishek Tiwari
 */
public class AWSSdkClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(GobblinAWSClusterLauncher.class);

  private static final Splitter SPLITTER = Splitter.on(',').omitEmptyStrings().trimResults();

  private static String AWS_ASG_SERVICE = "autoscaling";
  private static String AWS_EC2_SERVICE = "ec2";
  private static String AWS_S3_SERVICE = "s3";

  public static void createSecurityGroup(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String groupName,
      String description) {

    AmazonEC2 amazonEC2 = getEc2Client(awsClusterSecurityManager, region);
    try {
      CreateSecurityGroupRequest securityGroupRequest = new CreateSecurityGroupRequest()
          .withGroupName(groupName)
          .withDescription(description);
      amazonEC2.createSecurityGroup(securityGroupRequest);

      LOGGER.info("Created Security Group: " + groupName);
    } catch (AmazonServiceException ase) {
      // This might mean that security group is already created, hence ignore
      LOGGER.warn("Issue in creating security group", ase);
    }
  }

  public static void addPermissionsToSecurityGroup(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String groupName,
      String ipRanges,
      String ipProtocol,
      Integer fromPort,
      Integer toPort) {

    AmazonEC2 amazonEC2 = getEc2Client(awsClusterSecurityManager, region);

    IpPermission ipPermission = new IpPermission()
        .withIpRanges(ipRanges)
        .withIpProtocol(ipProtocol)
        .withFromPort(fromPort)
        .withToPort(toPort);
    AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest =
        new AuthorizeSecurityGroupIngressRequest()
            .withGroupName(groupName)
            .withIpPermissions(ipPermission);
    amazonEC2.authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);

    LOGGER.info("Added permissions: " + ipPermission + " to security group: " + groupName);
  }

  public static String createKeyValuePair(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String keyName) {

    AmazonEC2 amazonEC2 = getEc2Client(awsClusterSecurityManager, region);

    CreateKeyPairRequest createKeyPairRequest = new CreateKeyPairRequest().withKeyName(keyName);
    CreateKeyPairResult createKeyPairResult = amazonEC2.createKeyPair(createKeyPairRequest);
    KeyPair keyPair = createKeyPairResult.getKeyPair();
    String material = keyPair.getKeyMaterial();
    LOGGER.info("Created key: " + keyName);
    LOGGER.info("Created material: " + material);

    return material;
  }

  public static void createLaunchConfig(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String launchConfigName,
      String imageId,
      String instanceType,
      String keyName,
      String securityGroups,
      Optional<String> kernelId,
      Optional<String> ramdiskId,
      Optional<BlockDeviceMapping> blockDeviceMapping,
      Optional<String> iamInstanceProfile,
      Optional<InstanceMonitoring> instanceMonitoring,
      String userData) {

    AmazonAutoScaling autoScaling = getAmazonAutoScalingClient(awsClusterSecurityManager, region);

    CreateLaunchConfigurationRequest createLaunchConfigurationRequest = new CreateLaunchConfigurationRequest()
        .withLaunchConfigurationName(launchConfigName)
        .withImageId(imageId)
        .withInstanceType(instanceType)
        .withSecurityGroups(SPLITTER.splitToList(securityGroups))
        .withKeyName(keyName)
        .withUserData(userData);
    if (kernelId.isPresent()) {
      createLaunchConfigurationRequest = createLaunchConfigurationRequest
          .withKernelId(kernelId.get());
    }
    if (ramdiskId.isPresent()) {
      createLaunchConfigurationRequest = createLaunchConfigurationRequest
          .withRamdiskId(ramdiskId.get());
    }
    if (blockDeviceMapping.isPresent()) {
      createLaunchConfigurationRequest = createLaunchConfigurationRequest
          .withBlockDeviceMappings(blockDeviceMapping.get());
    }
    if (iamInstanceProfile.isPresent()) {
      createLaunchConfigurationRequest = createLaunchConfigurationRequest
          .withIamInstanceProfile(iamInstanceProfile.get());
    }
    if (instanceMonitoring.isPresent()) {
      createLaunchConfigurationRequest = createLaunchConfigurationRequest
          .withInstanceMonitoring(instanceMonitoring.get());
    }

    autoScaling.createLaunchConfiguration(createLaunchConfigurationRequest);

    LOGGER.info("Created Launch Configuration: " + launchConfigName);
  }

  public static void createAutoScalingGroup(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String groupName,
      String launchConfig,
      Integer minSize,
      Integer maxSize,
      Integer desiredCapacity,
      Optional<String> availabilityZones,
      Optional<Integer> cooldown,
      Optional<Integer> healthCheckGracePeriod,
      Optional<String> healthCheckType,
      Optional<String> loadBalancer,
      Optional<String> terminationPolicy,
      List<Tag> tags) {

    AmazonAutoScaling autoScaling = getAmazonAutoScalingClient(awsClusterSecurityManager, region);

    // Propagate ASG tags to EC2 instances launched under the ASG by default
    // (we want to ensure this, hence not configurable)
    List<Tag> tagsWithPropagationSet = Lists.newArrayList();
    for (Tag tag : tags) {
      tagsWithPropagationSet.add(tag.withPropagateAtLaunch(true));
    }

    CreateAutoScalingGroupRequest createAutoScalingGroupRequest = new CreateAutoScalingGroupRequest()
        .withAutoScalingGroupName(groupName)
        .withLaunchConfigurationName(launchConfig)
        .withMinSize(minSize)
        .withMaxSize(maxSize)
        .withDesiredCapacity(desiredCapacity)
        .withTags(tagsWithPropagationSet);
    if (availabilityZones.isPresent()) {
      createAutoScalingGroupRequest = createAutoScalingGroupRequest
          .withAvailabilityZones(SPLITTER.splitToList(availabilityZones.get()));
    }
    if (cooldown.isPresent()) {
      createAutoScalingGroupRequest = createAutoScalingGroupRequest
          .withDefaultCooldown(cooldown.get());
    }
    if (healthCheckGracePeriod.isPresent()) {
      createAutoScalingGroupRequest = createAutoScalingGroupRequest
          .withHealthCheckGracePeriod(healthCheckGracePeriod.get());
    }
    if (healthCheckType.isPresent()) {
      createAutoScalingGroupRequest = createAutoScalingGroupRequest
          .withHealthCheckType(healthCheckType.get());
    }
    if (loadBalancer.isPresent()) {
      createAutoScalingGroupRequest = createAutoScalingGroupRequest
          .withLoadBalancerNames(SPLITTER.splitToList(loadBalancer.get()));
    }
    if (terminationPolicy.isPresent()) {
      createAutoScalingGroupRequest = createAutoScalingGroupRequest
          .withTerminationPolicies(SPLITTER.splitToList(terminationPolicy.get()));
    }

    autoScaling.createAutoScalingGroup(createAutoScalingGroupRequest);

    LOGGER.info("Created AutoScalingGroup: " + groupName);
  }

  public static List<Instance> getInstancesForGroup(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String groupName,
      String status) {

    AmazonEC2 amazonEC2 = getEc2Client(awsClusterSecurityManager, region);

    final DescribeInstancesResult instancesResult = amazonEC2.describeInstances(new DescribeInstancesRequest()
        .withFilters(new Filter().withName("tag:aws:autoscaling:groupName").withValues(groupName)));

    List<Instance> instances = new ArrayList<>();
    for (Reservation reservation : instancesResult.getReservations()) {
      for (Instance instance : reservation.getInstances()) {
        if (null == status|| null == instance.getState()
            || status.equals(instance.getState().getName())) {
          instances.add(instance);
          LOGGER.info("Found instance: " + instance + " which qualified filter: " + status);
        } else {
          LOGGER.info("Found instance: " + instance + " but did not qualify for filter: " + status);
        }
      }
    }

    return instances;
  }

  public static List<AvailabilityZone> getAvailabilityZones(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region) {

    AmazonEC2 amazonEC2 = getEc2Client(awsClusterSecurityManager, region);

    final DescribeAvailabilityZonesResult describeAvailabilityZonesResult = amazonEC2.describeAvailabilityZones();
    final List<AvailabilityZone> availabilityZones = describeAvailabilityZonesResult.getAvailabilityZones();
    LOGGER.info("Found: " + availabilityZones.size() + " availability zone");

    return availabilityZones;
  }

  public static void downloadS3Object(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      S3ObjectSummary s3ObjectSummary,
      String targetDirectory)
      throws IOException {

    final AmazonS3 amazonS3 = getS3Client(awsClusterSecurityManager, region);

    final GetObjectRequest getObjectRequest = new GetObjectRequest(
        s3ObjectSummary.getBucketName(),
        s3ObjectSummary.getKey());

    final S3Object s3Object = amazonS3.getObject(getObjectRequest);

    final String targetFile = StringUtils.removeEnd(targetDirectory, File.separator) + File.separator + s3Object.getKey();
    FileUtils.copyInputStreamToFile(s3Object.getObjectContent(),
        new File(targetFile));

    LOGGER.info("S3 object downloaded to file: " + targetFile);
  }

  public static List<S3ObjectSummary> listS3Bucket(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region,
      String bucketName,
      String prefix) {

    final AmazonS3 amazonS3 = getS3Client(awsClusterSecurityManager, region);

    final ListObjectsRequest listObjectsRequest = new ListObjectsRequest()
        .withBucketName(bucketName)
        .withPrefix(prefix);

    final ObjectListing objectListing = amazonS3.listObjects(listObjectsRequest);
    LOGGER.info("S3 bucket listing for bucket: " + bucketName + " with prefix: " + prefix + " is: " + objectListing);

    return objectListing.getObjectSummaries();
  }


  public static AmazonEC2 getEc2Client(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region) {

    // TODO: Add client caching
    final AmazonEC2 ec2;
    if (awsClusterSecurityManager.isAssumeRoleEnabled()) {
      ec2 = new AmazonEC2Client(awsClusterSecurityManager.getBasicSessionCredentials());
    } else {
      ec2 = new AmazonEC2Client(awsClusterSecurityManager.getBasicAWSCredentials());
    }
    ec2.setRegion(region);

    return ec2;
  }

  public static AmazonAutoScaling getAmazonAutoScalingClient(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region) {

    // TODO: Add client caching
    final AmazonAutoScaling autoScaling;
    if (awsClusterSecurityManager.isAssumeRoleEnabled()) {
      autoScaling = new AmazonAutoScalingClient(awsClusterSecurityManager.getBasicSessionCredentials());
    } else {
      autoScaling = new AmazonAutoScalingClient(awsClusterSecurityManager.getBasicAWSCredentials());
    }
    autoScaling.setRegion(region);

    return autoScaling;
  }

  public static AmazonS3 getS3Client(AWSClusterSecurityManager awsClusterSecurityManager,
      Region region) {

    // TODO: Add client caching
    final AmazonS3 s3;
    if (awsClusterSecurityManager.isAssumeRoleEnabled()) {
      s3 = new AmazonS3Client(awsClusterSecurityManager.getBasicSessionCredentials());
    } else {
      s3 = new AmazonS3Client(awsClusterSecurityManager.getBasicAWSCredentials());
    }
    s3.setRegion(region);

    return s3;
  }
}
