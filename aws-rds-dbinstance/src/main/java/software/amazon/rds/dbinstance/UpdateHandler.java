package software.amazon.rds.dbinstance;

import com.amazonaws.util.CollectionUtils;
import com.amazonaws.util.StringUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.SecurityGroup;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.*;
import software.amazon.awssdk.utils.ImmutableMap;
import software.amazon.cloudformation.exceptions.CfnInvalidRequestException;
import software.amazon.cloudformation.proxy.*;
import software.amazon.rds.common.handler.Commons;
import software.amazon.rds.common.handler.Events;
import software.amazon.rds.common.handler.HandlerConfig;
import software.amazon.rds.common.handler.Tagging;
import software.amazon.rds.common.request.RequestValidationException;
import software.amazon.rds.common.request.ValidatedRequest;
import software.amazon.rds.common.request.Validations;
import software.amazon.rds.dbinstance.client.ApiVersion;
import software.amazon.rds.dbinstance.client.VersionedProxyClient;
import software.amazon.rds.dbinstance.status.DBInstanceStatus;
import software.amazon.rds.dbinstance.status.DBParameterGroupStatus;
import software.amazon.rds.dbinstance.util.ImmutabilityHelper;
import software.amazon.rds.dbinstance.util.ResourceModelHelper;
import software.amazon.rds.dbinstance.validators.AutomaticBackupReplicationValidator;
import software.amazon.rds.dbinstance.validators.OracleCustomSystemId;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class UpdateHandler extends BaseHandlerStd {

    public UpdateHandler() {
        this(DB_INSTANCE_HANDLER_CONFIG_36H);
    }

    public UpdateHandler(final HandlerConfig config) {
        super(config);
    }

    final String handlerOperation = "UPDATE";

    @Override
    protected void validateRequest(final ResourceHandlerRequest<ResourceModel> request) throws RequestValidationException {
        super.validateRequest(request);

        AutomaticBackupReplicationValidator.validateRequest(request.getDesiredResourceState());
    }

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ValidatedRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final VersionedProxyClient<RdsClient> rdsProxyClient,
            final VersionedProxyClient<Ec2Client> ec2ProxyClient
    ) {
        final ProxyClient<RdsClient> rdsClient = rdsProxyClient.defaultClient();
        DBInstance instance;

        try {
            instance = StringUtils.isNullOrEmpty(request.getPreviousResourceState().getEngine()) ?
                    fetchDBInstance(rdsClient, request.getPreviousResourceState()) : null;
        } catch (Exception ex) {
            return Commons.handleException(
                    ProgressEvent.progress(request.getPreviousResourceState(), callbackContext),
                    ex,
                    DEFAULT_DB_INSTANCE_ERROR_RULE_SET,
                    requestLogger
            );
        }

        if (!ImmutabilityHelper.isChangeMutable(request.getPreviousResourceState(), request.getDesiredResourceState(), instance, requestLogger)) {
            return ProgressEvent.failed(
                    request.getDesiredResourceState(),
                    callbackContext,
                    HandlerErrorCode.NotUpdatable,
                    "Resource is immutable"
            );
        }

        if (BooleanUtils.isTrue(request.getDriftable())) {
            return handleResourceDrift(proxy, request, callbackContext, rdsProxyClient, ec2ProxyClient);
        }

        final Tagging.TagSet previousTags = Tagging.TagSet.builder()
                .systemTags(Tagging.translateTagsToSdk(request.getPreviousSystemTags()))
                .stackTags(Tagging.translateTagsToSdk(request.getPreviousResourceTags()))
                .resourceTags(Translator.translateTagsToSdk(request.getPreviousResourceState().getTags()))
                .build();

        final Tagging.TagSet desiredTags = Tagging.TagSet.builder()
                .systemTags(Tagging.translateTagsToSdk(request.getSystemTags()))
                .stackTags(Tagging.translateTagsToSdk(request.getDesiredResourceTags()))
                .resourceTags(Translator.translateTagsToSdk(request.getDesiredResourceState().getTags()))
                .build();

        final Collection<DBInstanceRole> previousRoles = request.getPreviousResourceState().getAssociatedRoles();
        final Collection<DBInstanceRole> desiredRoles = request.getDesiredResourceState().getAssociatedRoles();

        return ProgressEvent.progress(request.getDesiredResourceState(), callbackContext)
                .then(progress -> {
                    try {
                        if (!Objects.equals(request.getDesiredResourceState().getEngineLifecycleSupport(),
                                request.getPreviousResourceState().getEngineLifecycleSupport()) &&
                                !request.getRollback()) {
                            throw new CfnInvalidRequestException("EngineLifecycleSupport cannot be modified.");
                        }
                    } catch (CfnInvalidRequestException e) {
                        return Commons.handleException(progress, e, MODIFY_DB_INSTANCE_ERROR_RULE_SET, requestLogger);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (shouldSetParameterGroupName(request)) {
                        return setParameterGroupName(rdsClient, progress);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (shouldSetDefaultVpcId(request)) {
                        return setDefaultVpcId(rdsClient, ec2ProxyClient.defaultClient(), progress);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (shouldUnsetMaxAllocatedStorage(request)) {
                        return unsetMaxAllocatedStorage(rdsClient, request, progress);
                    }
                    return progress;
                })
                .then(progress -> Commons.execOnce(progress, () -> {
                    try {
                        if (shouldAllocateStorage(request, rdsClient, progress)) {
                            if (isAllocatedStorageIncrease(request)) {
                                return allocateStorage(proxy, rdsClient, progress);
                            }
                        }
                        return progress;
                    } catch (Exception ex) {
                        return Commons.handleException(progress, ex, MODIFY_DB_INSTANCE_ERROR_RULE_SET, requestLogger);
                    }
                }, CallbackContext::isStorageAllocated, CallbackContext::setStorageAllocated))
                .then(progress -> Commons.execOnce(progress, () -> {
                    if (ResourceModelHelper.isReadReplicaPromotion(request.getPreviousResourceState(), request.getDesiredResourceState())) {
                        return promoteReadReplica(proxy, rdsClient, progress);
                    }
                    return progress;
                }, CallbackContext::isReadReplicaPromoted, CallbackContext::setReadReplicaPromoted))
                .then(progress -> Commons.execOnce(progress, () -> {
                    progress.getCallbackContext().timestampOnce(RESOURCE_UPDATED_AT, Instant.now());
                    return versioned(proxy, rdsProxyClient, progress, null, ImmutableMap.of(
                            ApiVersion.V12, (pxy, pcl, prg, tgs) -> updateDbInstanceV12(pxy, request, pcl, prg),
                            ApiVersion.DEFAULT, (pxy, pcl, prg, tgs) -> {
                            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient.defaultClient(), progress.getResourceModel());
                            return updateDbInstance(pxy, request, pcl, prg, dbInstance);
                        }
                    )).then(p -> Events.checkFailedEvents(
                            rdsProxyClient.defaultClient(),
                            p.getResourceModel().getDBInstanceIdentifier(),
                            SourceType.DB_INSTANCE,
                            p.getCallbackContext().getTimestamp(RESOURCE_UPDATED_AT),
                            p,
                            this::isFailureEvent,
                            requestLogger
                    ));
                }, CallbackContext::isUpdated, CallbackContext::setUpdated))
                .then(progress -> Commons.execOnce(progress, () -> {
                            if (shouldReboot(rdsClient, progress)) {
                                return rebootAwait(proxy, rdsClient, progress);
                            }
                            return progress;
                        }, CallbackContext::isRebooted, CallbackContext::setRebooted)
                )
                .then(progress -> Commons.execOnce(progress, () ->
                                updateAssociatedRoles(proxy, rdsClient, progress, previousRoles, desiredRoles),
                        CallbackContext::isUpdatedRoles, CallbackContext::setUpdatedRoles)
                )
                .then(progress -> Commons.execOnce(progress, () -> {
                    if ((ResourceModelHelper.shouldStopAutomaticBackupReplication(request.getPreviousResourceState(), request.getDesiredResourceState())
                            || ResourceModelHelper.shouldStartAutomaticBackupReplication(request.getPreviousResourceState(), request.getDesiredResourceState()))) {
                        final DBInstance dbInstance = fetchDBInstance(rdsProxyClient.defaultClient(), progress.getResourceModel());
                        if (StringUtils.isNullOrEmpty(callbackContext.getDbInstanceArn())) {
                            callbackContext.setDbInstanceArn(dbInstance.dbInstanceArn());
                        }
                        if (StringUtils.isNullOrEmpty(callbackContext.getKmsKeyId())) {
                            callbackContext.setKmsKeyId(dbInstance.kmsKeyId());
                        }
                    }
                    return progress;
                }, (m) -> !StringUtils.isNullOrEmpty(callbackContext.getDbInstanceArn()), (v, c) -> {
                }))
                .then(progress -> {
                    if (ResourceModelHelper.shouldStopAutomaticBackupReplication(request.getPreviousResourceState(), request.getDesiredResourceState())) {
                        return Commons.execOnce(progress, () -> stopAutomaticBackupReplicationInRegion(
                                callbackContext.getDbInstanceArn(),
                                proxy,
                                progress,
                                rdsProxyClient.defaultClient(),
                                ResourceModelHelper.getAutomaticBackupReplicationRegion(request.getPreviousResourceState())),
                            CallbackContext::isAutomaticBackupReplicationStopped, CallbackContext::setAutomaticBackupReplicationStopped);
                    }
                    return progress;
                })
                .then(progress -> {
                    if (ResourceModelHelper.shouldStartAutomaticBackupReplication(request.getPreviousResourceState(), request.getDesiredResourceState())) {
                        return Commons.execOnce(progress, () -> startAutomaticBackupReplicationInRegion(
                            callbackContext.getDbInstanceArn(),
                            ResourceModelHelper.getAutomaticBackupReplicationRetentionPeriod(request.getDesiredResourceState()),
                            ResourceModelHelper.getAutomaticBackupReplicationKmsKeyId(request.getDesiredResourceState()),
                            proxy,
                            progress,
                            rdsProxyClient.defaultClient(),
                            ResourceModelHelper.getAutomaticBackupReplicationRegion(request.getDesiredResourceState())),
                            CallbackContext::isAutomaticBackupReplicationStarted, CallbackContext::setAutomaticBackupReplicationStarted);
                    }
                    return progress;
                })
                .then(progress -> updateTags(proxy, rdsClient, progress, previousTags, desiredTags))
                .then(progress -> {
                    final ResourceModel model = request.getDesiredResourceState();
                    model.setTags(Translator.translateTagsFromSdk(Tagging.translateTagsToSdk(desiredTags)));
                    return Commons.reportResourceDrift(
                            model,
                            new ReadHandler().handleRequest(proxy, request, progress.getCallbackContext(), rdsProxyClient, ec2ProxyClient),
                            resourceTypeSchema,
                            requestLogger,
                            handlerOperation
                    );
                });
    }

    private ProgressEvent<ResourceModel, CallbackContext> handleResourceDrift(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final VersionedProxyClient<RdsClient> rdsProxyClient,
            final VersionedProxyClient<Ec2Client> ec2ProxyClient
    ) {
        return ProgressEvent.progress(request.getDesiredResourceState(), callbackContext)
                .then(progress -> {
                    if (shouldReboot(rdsProxyClient.defaultClient(), progress) ||
                            (DBInstancePredicates.isDBClusterMember(progress.getResourceModel()) && shouldRebootCluster(rdsProxyClient.defaultClient(), progress))) {
                        return rebootAwait(proxy, rdsProxyClient.defaultClient(), progress);
                    }
                    return progress;
                })
                .then(progress -> awaitDBParameterGroupInSyncStatus(proxy, rdsProxyClient.defaultClient(), progress))
                .then(progress -> awaitOptionGroupInSyncStatus(proxy, rdsProxyClient.defaultClient(), progress))
                .then(progress -> {
                    if (DBInstancePredicates.isDBClusterMember(progress.getResourceModel())) {
                        return awaitDBClusterParameterGroup(proxy, rdsProxyClient.defaultClient(), progress);
                    }
                    return progress;
                })
                .then(progress -> new ReadHandler().handleRequest(proxy, request, callbackContext, rdsProxyClient, ec2ProxyClient, requestLogger));
    }

    private boolean shouldReboot(
            final ProxyClient<RdsClient> proxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        try {
            final DBInstance dbInstance = fetchDBInstance(proxyClient, progress.getResourceModel());
            final boolean applyImmediately = ResourceModelHelper.shouldApplyImmediately(progress.getResourceModel());
            if (!CollectionUtils.isNullOrEmpty(dbInstance.dbParameterGroups())) {
                return applyImmediately && DBParameterGroupStatus.PendingReboot.equalsString(dbInstance.dbParameterGroups().get(0).parameterApplyStatus());
            }
        } catch (DbInstanceNotFoundException e) {
            return false;
        }
        return false;
    }

    private boolean shouldRebootCluster(
            final ProxyClient<RdsClient> proxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        final String dbInstanceIdentifier = progress.getResourceModel().getDBInstanceIdentifier();
        final DBCluster dbCluster = fetchDBCluster(proxyClient, progress.getResourceModel());
        final boolean applyImmediately =  ResourceModelHelper.shouldApplyImmediately(progress.getResourceModel());
        if (!CollectionUtils.isNullOrEmpty(dbCluster.dbClusterMembers())) {
            for (final DBClusterMember member : dbCluster.dbClusterMembers()) {
                if (dbInstanceIdentifier.equalsIgnoreCase(member.dbInstanceIdentifier())) {
                    return applyImmediately && DBParameterGroupStatus.PendingReboot.equalsString(member.dbClusterParameterGroupStatus());
                }
            }
        }
        return false;
    }

    private boolean shouldSetParameterGroupName(final ResourceHandlerRequest<ResourceModel> request) {
        final ResourceModel desiredModel = request.getDesiredResourceState();
        final ResourceModel previousModel = request.getPreviousResourceState();

        return ObjectUtils.notEqual(desiredModel.getDBParameterGroupName(), previousModel.getDBParameterGroupName()) &&
                ObjectUtils.notEqual(desiredModel.getEngineVersion(), previousModel.getEngineVersion()) &&
                BooleanUtils.isTrue(request.getRollback());
    }

    private ProgressEvent<ResourceModel, CallbackContext> unsetMaxAllocatedStorage(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ResourceHandlerRequest<ResourceModel> request,
            ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        // In order to disable an instance autoscaling, `MaxAllocatedStorage` property has to be unset.
        // The only way to unset `MaxAllocatedStorage` is to set it to `AllocatedStorage` value upon an update.
        // https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_PIOPS.StorageTypes.html#USER_PIOPS.Autoscaling
        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, request.getDesiredResourceState());
            request.getDesiredResourceState().setMaxAllocatedStorage(dbInstance.allocatedStorage());
        } catch (Exception exception) {
            return Commons.handleException(progress, exception, MODIFY_DB_INSTANCE_ERROR_RULE_SET, requestLogger);
        }
        return progress;
    }

    private ProgressEvent<ResourceModel, CallbackContext> allocateStorage(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        progress.getCallbackContext().setAllocatingStorage(true);
        return proxy.initiate("rds::increase-allocated-storage", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Translator::updateAllocatedStorageRequest)
                .backoffDelay(config.getBackoff())
                .makeServiceCall((modifyRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        modifyRequest,
                        proxyInvocation.client()::modifyDBInstance))
                .stabilize((request, response, proxyInvocation, model, context) -> isDBInstanceStabilizedAfterMutate(proxyInvocation, model, context))
                .handleError((request, exception, proxyInvocation, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        DEFAULT_DB_INSTANCE_ERROR_RULE_SET,
                        requestLogger
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> setParameterGroupName(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        final String dbParameterGroupName = progress.getResourceModel().getDBParameterGroupName();

        if (StringUtils.isNullOrEmpty(dbParameterGroupName)) {
            return progress;
        }

        final String engine = progress.getResourceModel().getEngine();
        final String engineVersion = progress.getResourceModel().getEngineVersion();

        final DescribeDbParameterGroupsResponse response = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbParameterGroupsRequest(dbParameterGroupName),
                rdsProxyClient.client()::describeDBParameterGroups
        );

        final Optional<DBParameterGroup> maybeDbParameterGroup = response.dbParameterGroups().stream().findFirst();

        if (!maybeDbParameterGroup.isPresent()) {
            return progress;
        }

        final String dbParameterGroupFamily = maybeDbParameterGroup.get().dbParameterGroupFamily();
        final DescribeDbEngineVersionsResponse describeDbEngineVersionsResponse = rdsProxyClient.injectCredentialsAndInvokeV2(
                Translator.describeDbEngineVersionsRequest(dbParameterGroupFamily, engine, engineVersion),
                rdsProxyClient.client()::describeDBEngineVersions
        );

        if (CollectionUtils.isNullOrEmpty(describeDbEngineVersionsResponse.dbEngineVersions())) {
            progress.getResourceModel().setDBParameterGroupName(null);
        } else {
            progress.getResourceModel().setDBParameterGroupName(dbParameterGroupName);
        }

        return progress;
    }

    private boolean shouldSetDefaultVpcId(final ResourceHandlerRequest<ResourceModel> request) {
        // DBCluster member instances inherit default vpc security groups from the corresponding umbrella cluster
        return !DBInstancePredicates.isDBClusterMember(request.getDesiredResourceState()) &&
                !DBInstancePredicates.isRdsCustomOracleInstance(request.getDesiredResourceState()) &&
                CollectionUtils.isNullOrEmpty(request.getDesiredResourceState().getVPCSecurityGroups());
    }

    private boolean shouldUnsetMaxAllocatedStorage(final ResourceHandlerRequest<ResourceModel> request) {
        return request.getPreviousResourceState() != null &&
                request.getPreviousResourceState().getMaxAllocatedStorage() != null &&
                request.getDesiredResourceState().getMaxAllocatedStorage() == null;
    }

    private boolean isAllocatedStorageIncrease(
            final ResourceHandlerRequest<ResourceModel> request
    ) {
        return BooleanUtils.isNotTrue(request.getRollback()) &&
                request.getPreviousResourceState() != null &&
                Translator.getAllocatedStorage(request.getDesiredResourceState()) > Translator.getAllocatedStorage(request.getPreviousResourceState());
    }

    private boolean shouldAllocateStorage(
            final ResourceHandlerRequest<ResourceModel> request,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        // need to store this in the context to prevent premature exit from storage-full-handle
        if (progress.getCallbackContext().isAllocatingStorage()) {
            return true;
        }
        final DBInstance instance = fetchDBInstance(rdsProxyClient, request.getDesiredResourceState());
        return DBInstanceStatus.StorageFull.equalsString(instance.dbInstanceStatus());
    }

    private ProgressEvent<ResourceModel, CallbackContext> setDefaultVpcId(
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProxyClient<Ec2Client> ec2ProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        SecurityGroup securityGroup;

        try {
            final DBInstance dbInstance = fetchDBInstance(rdsProxyClient, progress.getResourceModel());
            final String vpcId = dbInstance.dbSubnetGroup().vpcId();
            securityGroup = fetchSecurityGroup(ec2ProxyClient, vpcId, "default");
        } catch (Exception e) {
            return Commons.handleException(progress, e, DEFAULT_DB_INSTANCE_ERROR_RULE_SET, requestLogger);
        }

        if (securityGroup != null) {
            final String groupId = securityGroup.groupId();
            if (StringUtils.hasValue(groupId)) {
                progress.getResourceModel().setVPCSecurityGroups(Collections.singletonList(groupId));
            }
        }

        return progress;
    }

    private ProgressEvent<ResourceModel, CallbackContext> awaitDBParameterGroupInSyncStatus(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::stabilize-db-parameter-group-drift", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Function.identity())
                .backoffDelay(config.getBackoff())
                .makeServiceCall(NOOP_CALL)
                .stabilize((request, response, proxyInvocation, model, context) -> isDBParameterGroupStabilized(proxyInvocation, model))
                .handleError((request, exception, proxyInvocation, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        DEFAULT_DB_INSTANCE_ERROR_RULE_SET,
                        requestLogger
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> awaitOptionGroupInSyncStatus(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::stabilize-option-group-drift", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Function.identity())
                .backoffDelay(config.getBackoff())
                .makeServiceCall(NOOP_CALL)
                .stabilize((request, response, proxyInvocation, model, context) -> isOptionGroupStabilized(proxyInvocation, model))
                .handleError((request, exception, proxyInvocation, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        DEFAULT_DB_INSTANCE_ERROR_RULE_SET,
                        requestLogger
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> awaitDBClusterParameterGroup(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::stabilize-db-cluster-parameter-group-drift", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Function.identity())
                .backoffDelay(config.getBackoff())
                .makeServiceCall(NOOP_CALL)
                .stabilize((request, response, proxyInvocation, model, context) -> isDBClusterParameterGroupStabilized(proxyInvocation, model))
                .handleError((request, exception, proxyInvocation, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        DEFAULT_DB_INSTANCE_ERROR_RULE_SET,
                        requestLogger
                ))
                .progress();
    }

    private ProgressEvent<ResourceModel, CallbackContext> promoteReadReplica(
            final AmazonWebServicesClientProxy proxy,
            final ProxyClient<RdsClient> rdsProxyClient,
            final ProgressEvent<ResourceModel, CallbackContext> progress
    ) {
        return proxy.initiate("rds::promote-read-replica", rdsProxyClient, progress.getResourceModel(), progress.getCallbackContext())
                .translateToServiceRequest(Translator::promoteReadReplicaRequest)
                .backoffDelay(config.getBackoff())
                .makeServiceCall((modifyRequest, proxyInvocation) -> proxyInvocation.injectCredentialsAndInvokeV2(
                        modifyRequest,
                        proxyInvocation.client()::promoteReadReplica))
                .stabilize((request, response, proxyInvocation, model, context) -> isDBInstanceStabilizedAfterMutate(proxyInvocation, model, context))
                .handleError((request, exception, proxyInvocation, model, context) -> Commons.handleException(
                        ProgressEvent.progress(model, context),
                        exception,
                        DEFAULT_DB_INSTANCE_ERROR_RULE_SET,
                        requestLogger
                ))
                .progress();
    }
}
