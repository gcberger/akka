/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.sharding

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.Deploy
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.ClusterDomainEvent
import akka.cluster.ClusterEvent.ClusterShuttingDown
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.cluster.sharding.Shard.{ ShardCommand, StateChange }
import akka.cluster.singleton.ClusterSingletonManager
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.dispatch.ExecutionContexts
import akka.persistence._
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.ByteString
import akka.actor.Address
import java.util.Optional

/**
 * This extension provides sharding functionality of actors in a cluster.
 * The typical use case is when you have many stateful actors that together consume
 * more resources (e.g. memory) than fit on one machine. You need to distribute them across
 * several nodes in the cluster and you want to be able to interact with them using their
 * logical identifier, but without having to care about their physical location in the cluster,
 * which might also change over time. It could for example be actors representing Aggregate Roots in
 * Domain-Driven Design terminology. Here we call these actors "entities". These actors
 * typically have persistent (durable) state, but this feature is not limited to
 * actors with persistent state.
 *
 * In this context sharding means that actors with an identifier, so called entities,
 * can be automatically distributed across multiple nodes in the cluster. Each entity
 * actor runs only at one place, and messages can be sent to the entity without requiring
 * the sender to know the location of the destination actor. This is achieved by sending
 * the messages via a [[ShardRegion]] actor provided by this extension, which knows how
 * to route the message with the entity id to the final destination.
 *
 * This extension is supposed to be used by first, typically at system startup on each node
 * in the cluster, registering the supported entity types with the [[ClusterSharding#start]]
 * method and then the `ShardRegion` actor for a named entity type can be retrieved with
 * [[ClusterSharding#shardRegion]]. Messages to the entities are always sent via the local
 * `ShardRegion`. Some settings can be configured as described in the `akka.cluster.sharding`
 * section of the `reference.conf`.
 *
 * The `ShardRegion` actor is started on each node in the cluster, or group of nodes
 * tagged with a specific role. The `ShardRegion` is created with two application specific
 * functions to extract the entity identifier and the shard identifier from incoming messages.
 * A shard is a group of entities that will be managed together. For the first message in a
 * specific shard the `ShardRegion` request the location of the shard from a central coordinator,
 * the [[ShardCoordinator]]. The `ShardCoordinator` decides which `ShardRegion` that
 * owns the shard. The `ShardRegion` receives the decided home of the shard
 * and if that is the `ShardRegion` instance itself it will create a local child
 * actor representing the entity and direct all messages for that entity to it.
 * If the shard home is another `ShardRegion` instance messages will be forwarded
 * to that `ShardRegion` instance instead. While resolving the location of a
 * shard incoming messages for that shard are buffered and later delivered when the
 * shard home is known. Subsequent messages to the resolved shard can be delivered
 * to the target destination immediately without involving the `ShardCoordinator`.
 *
 * To make sure that at most one instance of a specific entity actor is running somewhere
 * in the cluster it is important that all nodes have the same view of where the shards
 * are located. Therefore the shard allocation decisions are taken by the central
 * `ShardCoordinator`, which is running as a cluster singleton, i.e. one instance on
 * the oldest member among all cluster nodes or a group of nodes tagged with a specific
 * role. The oldest member can be determined by [[akka.cluster.Member#isOlderThan]].
 *
 * The logic that decides where a shard is to be located is defined in a pluggable shard
 * allocation strategy. The default implementation [[ShardCoordinator.LeastShardAllocationStrategy]]
 * allocates new shards to the `ShardRegion` with least number of previously allocated shards.
 * This strategy can be replaced by an application specific implementation.
 *
 * To be able to use newly added members in the cluster the coordinator facilitates rebalancing
 * of shards, i.e. migrate entities from one node to another. In the rebalance process the
 * coordinator first notifies all `ShardRegion` actors that a handoff for a shard has started.
 * That means they will start buffering incoming messages for that shard, in the same way as if the
 * shard location is unknown. During the rebalance process the coordinator will not answer any
 * requests for the location of shards that are being rebalanced, i.e. local buffering will
 * continue until the handoff is completed. The `ShardRegion` responsible for the rebalanced shard
 * will stop all entities in that shard by sending `PoisonPill` to them. When all entities have
 * been terminated the `ShardRegion` owning the entities will acknowledge the handoff as completed
 * to the coordinator. Thereafter the coordinator will reply to requests for the location of
 * the shard and thereby allocate a new home for the shard and then buffered messages in the
 * `ShardRegion` actors are delivered to the new location. This means that the state of the entities
 * are not transferred or migrated. If the state of the entities are of importance it should be
 * persistent (durable), e.g. with `akka-persistence`, so that it can be recovered at the new
 * location.
 *
 * The logic that decides which shards to rebalance is defined in a pluggable shard
 * allocation strategy. The default implementation [[ShardCoordinator.LeastShardAllocationStrategy]]
 * picks shards for handoff from the `ShardRegion` with most number of previously allocated shards.
 * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
 * i.e. new members in the cluster. There is a configurable threshold of how large the difference
 * must be to begin the rebalancing. This strategy can be replaced by an application specific
 * implementation.
 *
 * The state of shard locations in the `ShardCoordinator` is persistent (durable) with
 * `akka-persistence` to survive failures. Since it is running in a cluster `akka-persistence`
 * must be configured with a distributed journal. When a crashed or unreachable coordinator
 * node has been removed (via down) from the cluster a new `ShardCoordinator` singleton
 * actor will take over and the state is recovered. During such a failure period shards
 * with known location are still available, while messages for new (unknown) shards
 * are buffered until the new `ShardCoordinator` becomes available.
 *
 * As long as a sender uses the same `ShardRegion` actor to deliver messages to an entity
 * actor the order of the messages is preserved. As long as the buffer limit is not reached
 * messages are delivered on a best effort basis, with at-most once delivery semantics,
 * in the same way as ordinary message sending. Reliable end-to-end messaging, with
 * at-least-once semantics can be added by using `AtLeastOnceDelivery` in `akka-persistence`.
 *
 * Some additional latency is introduced for messages targeted to new or previously
 * unused shards due to the round-trip to the coordinator. Rebalancing of shards may
 * also add latency. This should be considered when designing the application specific
 * shard resolution, e.g. to avoid too fine grained shards.
 *
 * The `ShardRegion` actor can also be started in proxy only mode, i.e. it will not
 * host any entities itself, but knows how to delegate messages to the right location.
 * A `ShardRegion` starts in proxy only mode if the roles of the node does not include
 * the node role specified in `akka.cluster.sharding.role` config property
 * or if the specified `entityProps` is `None`/`null`.
 *
 * If the state of the entities are persistent you may stop entities that are not used to
 * reduce memory consumption. This is done by the application specific implementation of
 * the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
 * If a message is already enqueued to the entity when it stops itself the enqueued message
 * in the mailbox will be dropped. To support graceful passivation without loosing such
 * messages the entity actor can send [[ShardRegion.Passivate]] to its parent `ShardRegion`.
 * The specified wrapped message in `Passivate` will be sent back to the entity, which is
 * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
 * between reception of `Passivate` and termination of the entity. Such buffered messages
 * are thereafter delivered to a new incarnation of the entity.
 *
 */
object ClusterSharding extends ExtensionId[ClusterSharding] with ExtensionIdProvider {
  override def get(system: ActorSystem): ClusterSharding = super.get(system)

  override def lookup = ClusterSharding

  override def createExtension(system: ExtendedActorSystem): ClusterSharding =
    new ClusterSharding(system)

}

/**
 * @see [[ClusterSharding$ ClusterSharding companion object]]
 */
class ClusterSharding(system: ExtendedActorSystem) extends Extension {
  import ClusterShardingGuardian._
  import ShardCoordinator.ShardAllocationStrategy
  import ShardCoordinator.LeastShardAllocationStrategy

  private val cluster = Cluster(system)

  private val regions: ConcurrentHashMap[String, ActorRef] = new ConcurrentHashMap
  private lazy val guardian = {
    val guardianName: String = system.settings.config.getString("akka.cluster.sharding.guardian-name")
    system.actorOf(Props[ClusterShardingGuardian], guardianName)
  }

  private[akka] def requireClusterRole(role: Option[String]): Unit =
    require(role.forall(cluster.selfRoles.contains),
      s"This cluster member [${cluster.selfAddress}] doesn't have the role [$role]")

  /**
   * Scala API: Register a named entity type by defining the [[akka.actor.Props]] of the entity actor
   * and functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @param allocationStrategy possibility to use a custom shard allocation and
   *   rebalancing logic
   * @param handOffStopMessage the message that will be sent to entities when they are to be stopped
   *   for a rebalance or graceful shutdown of a `ShardRegion`, e.g. `PoisonPill`.
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entityProps: Props,
    settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId,
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: Any): ActorRef = {

    requireClusterRole(settings.role)
    implicit val timeout = system.settings.CreationTimeout
    val startMsg = Start(typeName, entityProps, settings,
      extractEntityId, extractShardId, allocationStrategy, handOffStopMessage)
    val Started(shardRegion) = Await.result(guardian ? startMsg, timeout.duration)
    regions.put(typeName, shardRegion)
    shardRegion
  }

  /**
   * Register a named entity type by defining the [[akka.actor.Props]] of the entity actor and
   * functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * The default shard allocation strategy [[ShardCoordinator.LeastShardAllocationStrategy]]
   * is used. [[akka.actor.PoisonPill]] is used as `handOffStopMessage`.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entityProps: Props,
    settings: ClusterShardingSettings,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId): ActorRef = {

    val allocationStrategy = new LeastShardAllocationStrategy(
      settings.tuningParameters.leastShardAllocationRebalanceThreshold,
      settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance)

    start(typeName, entityProps, settings, extractEntityId, extractShardId, allocationStrategy, PoisonPill)
  }

  /**
   * Java/Scala API: Register a named entity type by defining the [[akka.actor.Props]] of the entity actor
   * and functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message
   * @param allocationStrategy possibility to use a custom shard allocation and
   *   rebalancing logic
   * @param handOffStopMessage the message that will be sent to entities when they are to be stopped
   *   for a rebalance or graceful shutdown of a `ShardRegion`, e.g. `PoisonPill`.
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entityProps: Props,
    settings: ClusterShardingSettings,
    messageExtractor: ShardRegion.MessageExtractor,
    allocationStrategy: ShardAllocationStrategy,
    handOffStopMessage: Any): ActorRef = {

    start(typeName, entityProps, settings,
      extractEntityId = {
        case msg if messageExtractor.entityId(msg) ne null ⇒
          (messageExtractor.entityId(msg), messageExtractor.entityMessage(msg))
      },
      extractShardId = msg ⇒ messageExtractor.shardId(msg),
      allocationStrategy = allocationStrategy,
      handOffStopMessage = handOffStopMessage)
  }

  /**
   * Java/Scala API: Register a named entity type by defining the [[akka.actor.Props]] of the entity actor
   * and functions to extract entity and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * The default shard allocation strategy [[ShardCoordinator.LeastShardAllocationStrategy]]
   * is used. [[akka.actor.PoisonPill]] is used as `handOffStopMessage`.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param entityProps the `Props` of the entity actors that will be created by the `ShardRegion`
   * @param settings configuration settings, see [[ClusterShardingSettings]]
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entityProps: Props,
    settings: ClusterShardingSettings,
    messageExtractor: ShardRegion.MessageExtractor): ActorRef = {

    val allocationStrategy = new LeastShardAllocationStrategy(
      settings.tuningParameters.leastShardAllocationRebalanceThreshold,
      settings.tuningParameters.leastShardAllocationMaxSimultaneousRebalance)

    start(typeName, entityProps, settings, messageExtractor, allocationStrategy, PoisonPill)
  }

  /**
   * Scala API: Register a named entity type `ShardRegion` on this node that will run in proxy only mode,
   * i.e. it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   * entity actors itself. The [[ShardRegion]] actor for this type can later be retrieved with the
   * [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param role specifies that this entity type is located on cluster nodes with a specific role.
   *   If the role is not specified all nodes in the cluster are used.
   * @param extractEntityId partial function to extract the entity id and the message to send to the
   *   entity from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param extractShardId function to determine the shard id for an incoming message, only messages
   *   that passed the `extractEntityId` will be used
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def startProxy(
    typeName: String,
    role: Option[String],
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId): ActorRef = {

    implicit val timeout = system.settings.CreationTimeout
    val settings = ClusterShardingSettings(system).withRole(role)
    val startMsg = StartProxy(typeName, settings, extractEntityId, extractShardId)
    val Started(shardRegion) = Await.result(guardian ? startMsg, timeout.duration)
    regions.put(typeName, shardRegion)
    shardRegion
  }

  /**
   * Java/Scala API: Register a named entity type `ShardRegion` on this node that will run in proxy only mode,
   * i.e. it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   * entity actors itself. The [[ShardRegion]] actor for this type can later be retrieved with the
   * [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entity type
   * @param role specifies that this entity type is located on cluster nodes with a specific role.
   *   If the role is not specified all nodes in the cluster are used.
   * @param messageExtractor functions to extract the entity id, shard id, and the message to send to the
   *   entity from the incoming message
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def startProxy(
    typeName: String,
    role: Optional[String],
    messageExtractor: ShardRegion.MessageExtractor): ActorRef = {

    startProxy(typeName, Option(role.orElse(null)),
      extractEntityId = {
        case msg if messageExtractor.entityId(msg) ne null ⇒
          (messageExtractor.entityId(msg), messageExtractor.entityMessage(msg))
      },
      extractShardId = msg ⇒ messageExtractor.shardId(msg))

  }

  /**
   * Retrieve the actor reference of the [[ShardRegion]] actor responsible for the named entity type.
   * The entity type must be registered with the [[#start]] method before it can be used here.
   * Messages to the entity is always sent via the `ShardRegion`.
   */
  def shardRegion(typeName: String): ActorRef = regions.get(typeName) match {
    case null ⇒ throw new IllegalArgumentException(s"Shard type [$typeName] must be started first")
    case ref  ⇒ ref
  }

}

/**
 * INTERNAL API.
 */
private[akka] object ClusterShardingGuardian {
  import ShardCoordinator.ShardAllocationStrategy
  final case class Start(typeName: String, entityProps: Props, settings: ClusterShardingSettings,
                         extractEntityId: ShardRegion.ExtractEntityId, extractShardId: ShardRegion.ExtractShardId,
                         allocationStrategy: ShardAllocationStrategy, handOffStopMessage: Any)
    extends NoSerializationVerificationNeeded
  final case class StartProxy(typeName: String, settings: ClusterShardingSettings,
                              extractEntityId: ShardRegion.ExtractEntityId, extractShardId: ShardRegion.ExtractShardId)
    extends NoSerializationVerificationNeeded
  final case class Started(shardRegion: ActorRef) extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API. [[ShardRegion]] and [[ShardCoordinator]] actors are created as children
 * of this actor.
 */
private[akka] class ClusterShardingGuardian extends Actor {
  import ClusterShardingGuardian._

  val cluster = Cluster(context.system)
  val sharding = ClusterSharding(context.system)

  private def coordinatorSingletonManagerName(encName: String): String =
    encName + "Coordinator"

  private def coordinatorPath(encName: String): String =
    (self.path / coordinatorSingletonManagerName(encName) / "singleton" / "coordinator").toStringWithoutAddress

  def receive = {
    case Start(typeName, entityProps, settings, extractEntityId, extractShardId, allocationStrategy, handOffStopMessage) ⇒
      import settings.role
      import settings.tuningParameters.coordinatorFailureBackoff
      val encName = URLEncoder.encode(typeName, ByteString.UTF_8)
      val cName = coordinatorSingletonManagerName(encName)
      val cPath = coordinatorPath(encName)
      val shardRegion = context.child(encName).getOrElse {
        if (context.child(cName).isEmpty) {
          val coordinatorProps = ShardCoordinator.props(typeName, settings, allocationStrategy)
          val singletonProps = BackoffSupervisor.props(
            childProps = coordinatorProps,
            childName = "coordinator",
            minBackoff = coordinatorFailureBackoff,
            maxBackoff = coordinatorFailureBackoff * 5,
            randomFactor = 0.2).withDeploy(Deploy.local)
          val singletonSettings = settings.coordinatorSingletonSettings
            .withSingletonName("singleton").withRole(role)
          context.actorOf(ClusterSingletonManager.props(
            singletonProps,
            terminationMessage = PoisonPill,
            singletonSettings),
            name = cName)
        }

        context.actorOf(ShardRegion.props(
          typeName = typeName,
          entityProps = entityProps,
          settings = settings,
          coordinatorPath = cPath,
          extractEntityId = extractEntityId,
          extractShardId = extractShardId,
          handOffStopMessage = handOffStopMessage),
          name = encName)
      }
      sender() ! Started(shardRegion)

    case StartProxy(typeName, settings, extractEntityId, extractShardId) ⇒
      val encName = URLEncoder.encode(typeName, ByteString.UTF_8)
      val cName = coordinatorSingletonManagerName(encName)
      val cPath = coordinatorPath(encName)
      val shardRegion = context.child(encName).getOrElse {
        context.actorOf(ShardRegion.proxyProps(
          typeName = typeName,
          settings = settings,
          coordinatorPath = cPath,
          extractEntityId = extractEntityId,
          extractShardId = extractShardId),
          name = encName)
      }
      sender() ! Started(shardRegion)

  }

}

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardRegion {

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor.
   */
  private[akka] def props(
    typeName: String,
    entityProps: Props,
    settings: ClusterShardingSettings,
    coordinatorPath: String,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId,
    handOffStopMessage: Any): Props =
    Props(new ShardRegion(typeName, Some(entityProps), settings, coordinatorPath, extractEntityId,
      extractShardId, handOffStopMessage)).withDeploy(Deploy.local)

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor
   * when using it in proxy only mode.
   */
  private[akka] def proxyProps(
    typeName: String,
    settings: ClusterShardingSettings,
    coordinatorPath: String,
    extractEntityId: ShardRegion.ExtractEntityId,
    extractShardId: ShardRegion.ExtractShardId): Props =
    Props(new ShardRegion(typeName, None, settings, coordinatorPath, extractEntityId, extractShardId, PoisonPill))
      .withDeploy(Deploy.local)

  /**
   * Marker type of entity identifier (`String`).
   */
  type EntityId = String
  /**
   * Marker type of shard identifier (`String`).
   */
  type ShardId = String
  /**
   * Marker type of application messages (`Any`).
   */
  type Msg = Any
  /**
   * Interface of the partial function used by the [[ShardRegion]] to
   * extract the entity id and the message to send to the entity from an
   * incoming message. The implementation is application specific.
   * If the partial function does not match the message will be
   * `unhandled`, i.e. posted as `Unhandled` messages on the event stream.
   * Note that the extracted  message does not have to be the same as the incoming
   * message to support wrapping in message envelope that is unwrapped before
   * sending to the entity actor.
   */
  type ExtractEntityId = PartialFunction[Msg, (EntityId, Msg)]
  /**
   * Interface of the function used by the [[ShardRegion]] to
   * extract the shard id from an incoming message.
   * Only messages that passed the [[ExtractEntityId]] will be used
   * as input to this function.
   */
  type ExtractShardId = Msg ⇒ ShardId

  /**
   * Java API: Interface of functions to extract entity id,
   * shard id, and the message to send to the entity from an
   * incoming message.
   */
  trait MessageExtractor {
    /**
     * Extract the entity id from an incoming `message`. If `null` is returned
     * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
     */
    def entityId(message: Any): String
    /**
     * Extract the message to send to the entity from an incoming `message`.
     * Note that the extracted message does not have to be the same as the incoming
     * message to support wrapping in message envelope that is unwrapped before
     * sending to the entity actor.
     */
    def entityMessage(message: Any): Any
    /**
     * Extract the entity id from an incoming `message`. Only messages that passed the [[#entityId]]
     * function will be used as input to this function.
     */
    def shardId(message: Any): String
  }

  /**
   * Convenience implementation of [[ShardRegion.MessageExtractor]] that
   * construct `shardId` based on the `hashCode` of the `entityId`. The number
   * of unique shards is limited by the given `maxNumberOfShards`.
   */
  abstract class HashCodeMessageExtractor(maxNumberOfShards: Int) extends MessageExtractor {
    /**
     * Default implementation pass on the message as is.
     */
    override def entityMessage(message: Any): Any = message

    override def shardId(message: Any): String =
      (math.abs(entityId(message).hashCode) % maxNumberOfShards).toString
  }

  sealed trait ShardRegionCommand

  /**
   * If the state of the entities are persistent you may stop entities that are not used to
   * reduce memory consumption. This is done by the application specific implementation of
   * the entity actors for example by defining receive timeout (`context.setReceiveTimeout`).
   * If a message is already enqueued to the entity when it stops itself the enqueued message
   * in the mailbox will be dropped. To support graceful passivation without loosing such
   * messages the entity actor can send this `Passivate` message to its parent `ShardRegion`.
   * The specified wrapped `stopMessage` will be sent back to the entity, which is
   * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
   * between reception of `Passivate` and termination of the entity. Such buffered messages
   * are thereafter delivered to a new incarnation of the entity.
   *
   * [[akka.actor.PoisonPill]] is a perfectly fine `stopMessage`.
   */
  @SerialVersionUID(1L) final case class Passivate(stopMessage: Any) extends ShardRegionCommand

  /*
   * Send this message to the `ShardRegion` actor to handoff all shards that are hosted by
   * the `ShardRegion` and then the `ShardRegion` actor will be stopped. You can `watch`
   * it to know when it is completed.
   */
  @SerialVersionUID(1L) final case object GracefulShutdown extends ShardRegionCommand

  /**
   * Java API: Send this message to the `ShardRegion` actor to handoff all shards that are hosted by
   * the `ShardRegion` and then the `ShardRegion` actor will be stopped. You can `watch`
   * it to know when it is completed.
   */
  def gracefulShutdownInstance = GracefulShutdown

  /*
   * Send this message to the `ShardRegion` actor to request for [[CurrentRegions]],
   * which contains the addresses of all registered regions.
   * Intended for testing purpose to see when cluster sharding is "ready".
   */
  @SerialVersionUID(1L) final case object GetCurrentRegions extends ShardRegionCommand

  def getCurrentRegionsInstance = GetCurrentRegions

  /**
   * Reply to `GetCurrentRegions`
   */
  @SerialVersionUID(1L) final case class CurrentRegions(regions: Set[Address]) {
    /**
     * Java API
     */
    def getRegions: java.util.Set[Address] = {
      import scala.collection.JavaConverters._
      regions.asJava
    }

  }

  private case object Retry extends ShardRegionCommand

  /**
   * When an remembering entities and the shard stops unexpected (e.g. persist failure), we
   * restart it after a back off using this message.
   */
  private final case class RestartShard(shardId: ShardId)

  private def roleOption(role: String): Option[String] =
    if (role == "") None else Option(role)

  /**
   * INTERNAL API. Sends stopMessage (e.g. `PoisonPill`) to the entities and when all of
   * them have terminated it replies with `ShardStopped`.
   */
  private[akka] class HandOffStopper(shard: String, replyTo: ActorRef, entities: Set[ActorRef], stopMessage: Any)
    extends Actor {
    import ShardCoordinator.Internal.ShardStopped

    entities.foreach { a ⇒
      context watch a
      a ! stopMessage
    }

    var remaining = entities

    def receive = {
      case Terminated(ref) ⇒
        remaining -= ref
        if (remaining.isEmpty) {
          replyTo ! ShardStopped(shard)
          context stop self
        }
    }
  }

  private[akka] def handOffStopperProps(
    shard: String, replyTo: ActorRef, entities: Set[ActorRef], stopMessage: Any): Props =
    Props(new HandOffStopper(shard, replyTo, entities, stopMessage)).withDeploy(Deploy.local)
}

/**
 * This actor creates children entity actors on demand for the shards that it is told to be
 * responsible for. It delegates messages targeted to other shards to the responsible
 * `ShardRegion` actor on other nodes.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class ShardRegion(
  typeName: String,
  entityProps: Option[Props],
  settings: ClusterShardingSettings,
  coordinatorPath: String,
  extractEntityId: ShardRegion.ExtractEntityId,
  extractShardId: ShardRegion.ExtractShardId,
  handOffStopMessage: Any) extends Actor with ActorLogging {

  import ShardCoordinator.Internal._
  import ShardRegion._
  import settings._
  import settings.tuningParameters._

  val cluster = Cluster(context.system)

  // sort by age, oldest first
  val ageOrdering = Ordering.fromLessThan[Member] { (a, b) ⇒ a.isOlderThan(b) }
  var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

  var regions = Map.empty[ActorRef, Set[ShardId]]
  var regionByShard = Map.empty[ShardId, ActorRef]
  var shardBuffers = Map.empty[ShardId, Vector[(Msg, ActorRef)]]
  var shards = Map.empty[ShardId, ActorRef]
  var shardsByRef = Map.empty[ActorRef, ShardId]
  var handingOff = Set.empty[ActorRef]
  var gracefulShutdownInProgress = false

  def totalBufferSize = shardBuffers.foldLeft(0) { (sum, entity) ⇒ sum + entity._2.size }

  import context.dispatcher
  val retryTask = context.system.scheduler.schedule(retryInterval, retryInterval, self, Retry)

  // subscribe to MemberEvent, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
    retryTask.cancel()
  }

  def matchingRole(member: Member): Boolean = role match {
    case None    ⇒ true
    case Some(r) ⇒ member.hasRole(r)
  }

  def coordinatorSelection: Option[ActorSelection] =
    membersByAge.headOption.map(m ⇒ context.actorSelection(RootActorPath(m.address) + coordinatorPath))

  var coordinator: Option[ActorRef] = None

  def changeMembers(newMembers: immutable.SortedSet[Member]): Unit = {
    val before = membersByAge.headOption
    val after = newMembers.headOption
    membersByAge = newMembers
    if (before != after) {
      if (log.isDebugEnabled)
        log.debug("Coordinator moved from [{}] to [{}]", before.map(_.address).getOrElse(""), after.map(_.address).getOrElse(""))
      coordinator = None
      register()
    }
  }

  def receive = {
    case Terminated(ref)                         ⇒ receiveTerminated(ref)
    case evt: ClusterDomainEvent                 ⇒ receiveClusterEvent(evt)
    case state: CurrentClusterState              ⇒ receiveClusterState(state)
    case msg: CoordinatorMessage                 ⇒ receiveCoordinatorMessage(msg)
    case cmd: ShardRegionCommand                 ⇒ receiveCommand(cmd)
    case msg if extractEntityId.isDefinedAt(msg) ⇒ deliverMessage(msg, sender())
  }

  def receiveClusterState(state: CurrentClusterState): Unit = {
    changeMembers(immutable.SortedSet.empty(ageOrdering) ++ state.members.filter(m ⇒
      m.status == MemberStatus.Up && matchingRole(m)))
  }

  def receiveClusterEvent(evt: ClusterDomainEvent): Unit = evt match {
    case MemberUp(m) ⇒
      if (matchingRole(m))
        changeMembers(membersByAge + m)

    case MemberRemoved(m, _) ⇒
      if (m.uniqueAddress == cluster.selfUniqueAddress)
        context.stop(self)
      else if (matchingRole(m))
        changeMembers(membersByAge - m)

    case _ ⇒ unhandled(evt)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HostShard(shard) ⇒
      log.debug("Host Shard [{}] ", shard)
      regionByShard = regionByShard.updated(shard, self)
      regions = regions.updated(self, regions.getOrElse(self, Set.empty) + shard)

      //Start the shard, if already started this does nothing
      getShard(shard)
      deliverBufferedMessages(shard)

      sender() ! ShardStarted(shard)

    case ShardHome(shard, ref) ⇒
      log.debug("Shard [{}] located at [{}]", shard, ref)
      regionByShard.get(shard) match {
        case Some(r) if r == self && ref != self ⇒
          // should not happen, inconsistency between ShardRegion and ShardCoordinator
          throw new IllegalStateException(s"Unexpected change of shard [${shard}] from self to [${ref}]")
        case _ ⇒
      }
      regionByShard = regionByShard.updated(shard, ref)
      regions = regions.updated(ref, regions.getOrElse(ref, Set.empty) + shard)

      if (ref != self)
        context.watch(ref)

      deliverBufferedMessages(shard)

    case RegisterAck(coord) ⇒
      context.watch(coord)
      coordinator = Some(coord)
      requestShardBufferHomes()

    case BeginHandOff(shard) ⇒
      log.debug("BeginHandOff shard [{}]", shard)
      if (regionByShard.contains(shard)) {
        val regionRef = regionByShard(shard)
        val updatedShards = regions(regionRef) - shard
        if (updatedShards.isEmpty) regions -= regionRef
        else regions = regions.updated(regionRef, updatedShards)
        regionByShard -= shard
      }
      sender() ! BeginHandOffAck(shard)

    case msg @ HandOff(shard) ⇒
      log.debug("HandOff shard [{}]", shard)

      // must drop requests that came in between the BeginHandOff and now,
      // because they might be forwarded from other regions and there
      // is a risk or message re-ordering otherwise
      if (shardBuffers.contains(shard))
        shardBuffers -= shard

      if (shards.contains(shard)) {
        handingOff += shards(shard)
        shards(shard) forward msg
      } else
        sender() ! ShardStopped(shard)

    case _ ⇒ unhandled(msg)

  }

  def receiveCommand(cmd: ShardRegionCommand): Unit = cmd match {
    case Retry ⇒
      if (coordinator.isEmpty)
        register()
      else {
        sendGracefulShutdownToCoordinator()
        requestShardBufferHomes()
      }

    case GracefulShutdown ⇒
      log.debug("Starting graceful shutdown of region and all its shards")
      gracefulShutdownInProgress = true
      sendGracefulShutdownToCoordinator()

    case GetCurrentRegions ⇒
      coordinator match {
        case Some(c) ⇒ c.forward(GetCurrentRegions)
        case None    ⇒ sender() ! CurrentRegions(Set.empty)
      }

    case _ ⇒ unhandled(cmd)
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (coordinator.exists(_ == ref))
      coordinator = None
    else if (regions.contains(ref)) {
      val shards = regions(ref)
      regionByShard --= shards
      regions -= ref
      if (log.isDebugEnabled)
        log.debug("Region [{}] with shards [{}] terminated", ref, shards.mkString(", "))
    } else if (shardsByRef.contains(ref)) {
      val shardId: ShardId = shardsByRef(ref)

      shardsByRef = shardsByRef - ref
      shards = shards - shardId
      if (handingOff.contains(ref)) {
        handingOff = handingOff - ref
        log.debug("Shard [{}] handoff complete", shardId)
      } else {
        // if persist fails it will stop
        log.debug("Shard [{}]  terminated while not being handed off", shardId)
        if (rememberEntities) {
          context.system.scheduler.scheduleOnce(shardFailureBackoff, self, RestartShard(shardId))
        }
      }

      if (gracefulShutdownInProgress && shards.isEmpty && shardBuffers.isEmpty)
        context.stop(self) // all shards have been rebalanced, complete graceful shutdown
    }
  }

  def register(): Unit = {
    coordinatorSelection.foreach(_ ! registrationMessage)
  }

  def registrationMessage: Any =
    if (entityProps.isDefined) Register(self) else RegisterProxy(self)

  def requestShardBufferHomes(): Unit = {
    shardBuffers.foreach {
      case (shard, _) ⇒ coordinator.foreach { c ⇒
        log.debug("Retry request for shard [{}] homes", shard)
        c ! GetShardHome(shard)
      }
    }
  }

  def deliverBufferedMessages(shard: String): Unit = {
    shardBuffers.get(shard) match {
      case Some(buf) ⇒
        buf.foreach { case (msg, snd) ⇒ deliverMessage(msg, snd) }
        shardBuffers -= shard
      case None ⇒
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit =
    msg match {
      case RestartShard(shardId) ⇒
        regionByShard.get(shardId) match {
          case Some(ref) ⇒
            if (ref == self)
              getShard(shardId)
          case None ⇒
            if (!shardBuffers.contains(shardId)) {
              log.debug("Request shard [{}] home", shardId)
              coordinator.foreach(_ ! GetShardHome(shardId))
            }
            val buf = shardBuffers.getOrElse(shardId, Vector.empty)
            shardBuffers = shardBuffers.updated(shardId, buf :+ ((msg, snd)))
        }

      case _ ⇒
        val shardId = extractShardId(msg)
        regionByShard.get(shardId) match {
          case Some(ref) if ref == self ⇒
            getShard(shardId).tell(msg, snd)
          case Some(ref) ⇒
            log.debug("Forwarding request for shard [{}] to [{}]", shardId, ref)
            ref.tell(msg, snd)
          case None if (shardId == null || shardId == "") ⇒
            log.warning("Shard must not be empty, dropping message [{}]", msg.getClass.getName)
            context.system.deadLetters ! msg
          case None ⇒
            if (!shardBuffers.contains(shardId)) {
              log.debug("Request shard [{}] home", shardId)
              coordinator.foreach(_ ! GetShardHome(shardId))
            }
            if (totalBufferSize >= bufferSize) {
              log.debug("Buffer is full, dropping message for shard [{}]", shardId)
              context.system.deadLetters ! msg
            } else {
              val buf = shardBuffers.getOrElse(shardId, Vector.empty)
              shardBuffers = shardBuffers.updated(shardId, buf :+ ((msg, snd)))
            }
        }
    }

  def getShard(id: ShardId): ActorRef = shards.getOrElse(
    id,
    entityProps match {
      case Some(props) ⇒
        log.debug("Starting shard [{}] in region", id)

        val name = URLEncoder.encode(id, "utf-8")
        val shard = context.watch(context.actorOf(
          Shard.props(
            typeName,
            id,
            props,
            settings,
            extractEntityId,
            extractShardId,
            handOffStopMessage),
          name))
        shards = shards.updated(id, shard)
        shardsByRef = shardsByRef.updated(shard, id)
        shard
      case None ⇒
        throw new IllegalStateException("Shard must not be allocated to a proxy only ShardRegion")
    })

  def sendGracefulShutdownToCoordinator(): Unit =
    if (gracefulShutdownInProgress)
      coordinator.foreach(_ ! GracefulShutdownReq(self))
}

/**
 * INTERNAL API
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] object Shard {
  import ShardRegion.EntityId

  /**
   * A Shard command
   */
  sealed trait ShardCommand

  /**
   * When an remembering entities and the entity stops without issuing a `Passivate`, we
   * restart it after a back off using this message.
   */
  final case class RestartEntity(entity: EntityId) extends ShardCommand

  /**
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange { val entityId: EntityId }

  /**
   * `State` change for starting an entity in this `Shard`
   */
  @SerialVersionUID(1L) final case class EntityStarted(entityId: EntityId) extends StateChange

  /**
   * `State` change for an entity which has terminated.
   */
  @SerialVersionUID(1L) final case class EntityStopped(entityId: EntityId) extends StateChange

  object State {
    val Empty = State()
  }

  /**
   * Persistent state of the Shard.
   */
  @SerialVersionUID(1L) final case class State private (
    entities: Set[EntityId] = Set.empty)

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Shard]] actor.
   */
  def props(typeName: String,
            shardId: ShardRegion.ShardId,
            entityProps: Props,
            settings: ClusterShardingSettings,
            extractEntityId: ShardRegion.ExtractEntityId,
            extractShardId: ShardRegion.ExtractShardId,
            handOffStopMessage: Any): Props =
    Props(new Shard(typeName, shardId, entityProps, settings, extractEntityId, extractShardId, handOffStopMessage))
      .withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 *
 * This actor creates children entity actors on demand that it is told to be
 * responsible for.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class Shard(
  typeName: String,
  shardId: ShardRegion.ShardId,
  entityProps: Props,
  settings: ClusterShardingSettings,
  extractEntityId: ShardRegion.ExtractEntityId,
  extractShardId: ShardRegion.ExtractShardId,
  handOffStopMessage: Any) extends PersistentActor with ActorLogging {

  import ShardRegion.{ handOffStopperProps, EntityId, Msg, Passivate }
  import ShardCoordinator.Internal.{ HandOff, ShardStopped }
  import Shard.{ State, RestartEntity, EntityStopped, EntityStarted }
  import akka.cluster.sharding.ShardCoordinator.Internal.CoordinatorMessage
  import akka.cluster.sharding.ShardRegion.ShardRegionCommand
  import akka.persistence.RecoveryCompleted

  import settings.rememberEntities
  import settings.tuningParameters._

  override def persistenceId = s"/sharding/${typeName}Shard/${shardId}"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  var state = State.Empty
  var idByRef = Map.empty[ActorRef, EntityId]
  var refById = Map.empty[EntityId, ActorRef]
  var passivating = Set.empty[ActorRef]
  var messageBuffers = Map.empty[EntityId, Vector[(Msg, ActorRef)]]
  var persistCount = 0

  var handOffStopper: Option[ActorRef] = None

  def totalBufferSize = messageBuffers.foldLeft(0) { (sum, entity) ⇒ sum + entity._2.size }

  def processChange[A](event: A)(handler: A ⇒ Unit): Unit =
    if (rememberEntities) {
      saveSnapshotWhenNeeded()
      persist(event)(handler)
    } else handler(event)

  def saveSnapshotWhenNeeded(): Unit = {
    persistCount += 1
    if (persistCount % snapshotAfter == 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(state)
    }
  }

  override def receiveRecover: Receive = {
    case EntityStarted(id) if rememberEntities ⇒ state = state.copy(state.entities + id)
    case EntityStopped(id) if rememberEntities ⇒ state = state.copy(state.entities - id)
    case SnapshotOffer(_, snapshot: State)     ⇒ state = snapshot
    case RecoveryCompleted                     ⇒ state.entities foreach getEntity
  }

  override def receiveCommand: Receive = {
    case Terminated(ref)                         ⇒ receiveTerminated(ref)
    case msg: CoordinatorMessage                 ⇒ receiveCoordinatorMessage(msg)
    case msg: ShardCommand                       ⇒ receiveShardCommand(msg)
    case msg: ShardRegionCommand                 ⇒ receiveShardRegionCommand(msg)
    case msg if extractEntityId.isDefinedAt(msg) ⇒ deliverMessage(msg, sender())
  }

  def receiveShardCommand(msg: ShardCommand): Unit = msg match {
    case RestartEntity(id) ⇒ getEntity(id)
  }

  def receiveShardRegionCommand(msg: ShardRegionCommand): Unit = msg match {
    case Passivate(stopMessage) ⇒ passivate(sender(), stopMessage)
    case _                      ⇒ unhandled(msg)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HandOff(`shardId`) ⇒ handOff(sender())
    case HandOff(shard)     ⇒ log.warning("Shard [{}] can not hand off for another Shard [{}]", shardId, shard)
    case _                  ⇒ unhandled(msg)
  }

  def handOff(replyTo: ActorRef): Unit = handOffStopper match {
    case Some(_) ⇒ log.warning("HandOff shard [{}] received during existing handOff", shardId)
    case None ⇒
      log.debug("HandOff shard [{}]", shardId)

      if (state.entities.nonEmpty) {
        handOffStopper = Some(context.watch(context.actorOf(
          handOffStopperProps(shardId, replyTo, idByRef.keySet, handOffStopMessage))))

        //During hand off we only care about watching for termination of the hand off stopper
        context become {
          case Terminated(ref) ⇒ receiveTerminated(ref)
        }
      } else {
        replyTo ! ShardStopped(shardId)
        context stop self
      }
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (handOffStopper.exists(_ == ref)) {
      context stop self
    } else if (idByRef.contains(ref) && handOffStopper.isEmpty) {
      val id = idByRef(ref)
      if (messageBuffers.getOrElse(id, Vector.empty).nonEmpty) {
        //Note; because we're not persisting the EntityStopped, we don't need
        // to persist the EntityStarted either.
        log.debug("Starting entity [{}] again, there are buffered messages for it", id)
        sendMsgBuffer(EntityStarted(id))
      } else {
        if (rememberEntities && !passivating.contains(ref)) {
          log.debug("Entity [{}] stopped without passivating, will restart after backoff", id)
          import context.dispatcher
          context.system.scheduler.scheduleOnce(entityRestartBackoff, self, RestartEntity(id))
        } else processChange(EntityStopped(id))(passivateCompleted)
      }

      passivating = passivating - ref
    }
  }

  def passivate(entity: ActorRef, stopMessage: Any): Unit = {
    idByRef.get(entity) match {
      case Some(id) if !messageBuffers.contains(id) ⇒
        log.debug("Passivating started on entity {}", id)

        passivating = passivating + entity
        messageBuffers = messageBuffers.updated(id, Vector.empty)
        entity ! stopMessage

      case _ ⇒ //ignored
    }
  }

  // EntityStopped persistence handler
  def passivateCompleted(event: EntityStopped): Unit = {
    log.debug("Entity stopped [{}]", event.entityId)

    val ref = refById(event.entityId)
    idByRef -= ref
    refById -= event.entityId

    state = state.copy(state.entities - event.entityId)
    messageBuffers = messageBuffers - event.entityId
  }

  // EntityStarted persistence handler
  def sendMsgBuffer(event: EntityStarted): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrElse(event.entityId, Vector.empty)
    messageBuffers = messageBuffers - event.entityId

    if (messages.nonEmpty) {
      log.debug("Sending message buffer for entity [{}] ([{}] messages)", event.entityId, messages.size)
      getEntity(event.entityId)

      //Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages foreach {
        case (msg, snd) ⇒ deliverMessage(msg, snd)
      }
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (id, payload) = extractEntityId(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      messageBuffers.get(id) match {
        case None ⇒ deliverTo(id, msg, payload, snd)

        case Some(buf) if totalBufferSize >= bufferSize ⇒
          log.debug("Buffer is full, dropping message for entity [{}]", id)
          context.system.deadLetters ! msg

        case Some(buf) ⇒
          log.debug("Message for entity [{}] buffered", id)
          messageBuffers = messageBuffers.updated(id, buf :+ ((msg, snd)))
      }
    }
  }

  def deliverTo(id: EntityId, msg: Any, payload: Msg, snd: ActorRef): Unit = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name) match {
      case Some(actor) ⇒
        actor.tell(payload, snd)

      case None if rememberEntities ⇒
        //Note; we only do this if remembering, otherwise the buffer is an overhead
        messageBuffers = messageBuffers.updated(id, Vector((msg, snd)))
        saveSnapshotWhenNeeded()
        persist(EntityStarted(id))(sendMsgBuffer)

      case None ⇒
        getEntity(id).tell(payload, snd)
    }
  }

  def getEntity(id: EntityId): ActorRef = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name).getOrElse {
      log.debug("Starting entity [{}] in shard [{}]", id, shardId)

      val a = context.watch(context.actorOf(entityProps, name))
      idByRef = idByRef.updated(a, id)
      refById = refById.updated(id, a)
      state = state.copy(state.entities + id)
      a
    }
  }
}

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardCoordinator {

  import ShardRegion.ShardId

  /**
   * INTERNAL API
   * Factory method for the [[akka.actor.Props]] of the [[ShardCoordinator]] actor.
   */
  private[akka] def props(typeName: String, settings: ClusterShardingSettings,
                          allocationStrategy: ShardAllocationStrategy): Props =
    Props(new ShardCoordinator(typeName: String, settings, allocationStrategy)).withDeploy(Deploy.local)

  /**
   * Interface of the pluggable shard allocation and rebalancing logic used by the [[ShardCoordinator]].
   *
   * Java implementations should extend [[AbstractShardAllocationStrategy]].
   */
  trait ShardAllocationStrategy extends NoSerializationVerificationNeeded {
    /**
     * Invoked when the location of a new shard is to be decided.
     * @param requester actor reference to the [[ShardRegion]] that requested the location of the
     *   shard, can be returned if preference should be given to the node where the shard was first accessed
     * @param shardId the id of the shard to allocate
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @return a `Future` of the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of
     *   the references included in the `currentShardAllocations` parameter
     */
    def allocateShard(requester: ActorRef, shardId: ShardId,
                      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef]

    /**
     * Invoked periodically to decide which shards to rebalance to another location.
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @param rebalanceInProgress set of shards that are currently being rebalanced, i.e.
     *   you should not include these in the returned set
     * @return a `Future` of the shards to be migrated, may be empty to skip rebalance in this round
     */
    def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                  rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]]
  }

  /**
   * Java API: Java implementations of custom shard allocation and rebalancing logic used by the [[ShardCoordinator]]
   * should extend this abstract class and implement the two methods.
   */
  abstract class AbstractShardAllocationStrategy extends ShardAllocationStrategy {
    override final def allocateShard(requester: ActorRef, shardId: ShardId,
                                     currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {

      import scala.collection.JavaConverters._
      allocateShard(requester, shardId, currentShardAllocations.asJava)
    }

    override final def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                                 rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {
      import scala.collection.JavaConverters._
      implicit val ec = ExecutionContexts.sameThreadExecutionContext
      rebalance(currentShardAllocations.asJava, rebalanceInProgress.asJava).map(_.asScala.toSet)
    }

    /**
     * Invoked when the location of a new shard is to be decided.
     * @param requester actor reference to the [[ShardRegion]] that requested the location of the
     *   shard, can be returned if preference should be given to the node where the shard was first accessed
     * @param shardId the id of the shard to allocate
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @return a `Future` of the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of
     *   the references included in the `currentShardAllocations` parameter
     */
    def allocateShard(requester: ActorRef, shardId: String,
                      currentShardAllocations: java.util.Map[ActorRef, immutable.IndexedSeq[String]]): Future[ActorRef]

    /**
     * Invoked periodically to decide which shards to rebalance to another location.
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @param rebalanceInProgress set of shards that are currently being rebalanced, i.e.
     *   you should not include these in the returned set
     * @return a `Future` of the shards to be migrated, may be empty to skip rebalance in this round
     */
    def rebalance(currentShardAllocations: java.util.Map[ActorRef, immutable.IndexedSeq[String]],
                  rebalanceInProgress: java.util.Set[String]): Future[java.util.Set[String]]
  }

  private val emptyRebalanceResult = Future.successful(Set.empty[ShardId])

  /**
   * The default implementation of [[ShardCoordinator.LeastShardAllocationStrategy]]
   * allocates new shards to the `ShardRegion` with least number of previously allocated shards.
   * It picks shards for rebalancing handoff from the `ShardRegion` with most number of previously allocated shards.
   * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
   * i.e. new members in the cluster. There is a configurable threshold of how large the difference
   * must be to begin the rebalancing. The number of ongoing rebalancing processes can be limited.
   */
  @SerialVersionUID(1L)
  class LeastShardAllocationStrategy(rebalanceThreshold: Int, maxSimultaneousRebalance: Int)
    extends ShardAllocationStrategy with Serializable {

    override def allocateShard(requester: ActorRef, shardId: ShardId,
                               currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): Future[ActorRef] = {
      val (regionWithLeastShards, _) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }
      Future.successful(regionWithLeastShards)
    }

    override def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                           rebalanceInProgress: Set[ShardId]): Future[Set[ShardId]] = {
      if (rebalanceInProgress.size < maxSimultaneousRebalance) {
        val (regionWithLeastShards, leastShards) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }
        val mostShards = currentShardAllocations.collect {
          case (_, v) ⇒ v.filterNot(s ⇒ rebalanceInProgress(s))
        }.maxBy(_.size)
        if (mostShards.size - leastShards.size >= rebalanceThreshold)
          Future.successful(Set(mostShards.head))
        else
          emptyRebalanceResult
      } else emptyRebalanceResult
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    /**
     * Messages sent to the coordinator
     */
    sealed trait CoordinatorCommand
    /**
     * Messages sent from the coordinator
     */
    sealed trait CoordinatorMessage
    /**
     * `ShardRegion` registers to `ShardCoordinator`, until it receives [[RegisterAck]].
     */
    @SerialVersionUID(1L) final case class Register(shardRegion: ActorRef) extends CoordinatorCommand
    /**
     * `ShardRegion` in proxy only mode registers to `ShardCoordinator`, until it receives [[RegisterAck]].
     */
    @SerialVersionUID(1L) final case class RegisterProxy(shardRegionProxy: ActorRef) extends CoordinatorCommand
    /**
     * Acknowledgement from `ShardCoordinator` that [[Register]] or [[RegisterProxy]] was successful.
     */
    @SerialVersionUID(1L) final case class RegisterAck(coordinator: ActorRef) extends CoordinatorMessage
    /**
     * `ShardRegion` requests the location of a shard by sending this message
     * to the `ShardCoordinator`.
     */
    @SerialVersionUID(1L) final case class GetShardHome(shard: ShardId) extends CoordinatorCommand
    /**
     * `ShardCoordinator` replies with this message for [[GetShardHome]] requests.
     */
    @SerialVersionUID(1L) final case class ShardHome(shard: ShardId, ref: ActorRef) extends CoordinatorMessage
    /**
     * `ShardCoordinator` informs a `ShardRegion` that it is hosting this shard
     */
    @SerialVersionUID(1L) final case class HostShard(shard: ShardId) extends CoordinatorMessage
    /**
     * `ShardRegion` replies with this message for [[HostShard]] requests which lead to it hosting the shard
     */
    @SerialVersionUID(1l) final case class ShardStarted(shard: ShardId) extends CoordinatorMessage
    /**
     * `ShardCoordinator` initiates rebalancing process by sending this message
     * to all registered `ShardRegion` actors (including proxy only). They are
     * supposed to discard their known location of the shard, i.e. start buffering
     * incoming messages for the shard. They reply with [[BeginHandOffAck]].
     * When all have replied the `ShardCoordinator` continues by sending
     * `HandOff` to the `ShardRegion` responsible for the shard.
     */
    @SerialVersionUID(1L) final case class BeginHandOff(shard: ShardId) extends CoordinatorMessage
    /**
     * Acknowledgement of [[BeginHandOff]]
     */
    @SerialVersionUID(1L) final case class BeginHandOffAck(shard: ShardId) extends CoordinatorCommand
    /**
     * When all `ShardRegion` actors have acknowledged the `BeginHandOff` the
     * `ShardCoordinator` sends this message to the `ShardRegion` responsible for the
     * shard. The `ShardRegion` is supposed to stop all entities in that shard and when
     * all entities have terminated reply with `ShardStopped` to the `ShardCoordinator`.
     */
    @SerialVersionUID(1L) final case class HandOff(shard: ShardId) extends CoordinatorMessage
    /**
     * Reply to `HandOff` when all entities in the shard have been terminated.
     */
    @SerialVersionUID(1L) final case class ShardStopped(shard: ShardId) extends CoordinatorCommand

    /**
     * Result of `allocateShard` is piped to self with this message.
     */
    @SerialVersionUID(1L) final case class AllocateShardResult(
      shard: ShardId, shardRegion: Option[ActorRef], getShardHomeSender: ActorRef) extends CoordinatorCommand

    /**
     * Result of `rebalance` is piped to self with this message.
     */
    @SerialVersionUID(1L) final case class RebalanceResult(shards: Set[ShardId]) extends CoordinatorCommand

    /**
     * `ShardRegion` requests full handoff to be able to shutdown gracefully.
     */
    @SerialVersionUID(1L) final case class GracefulShutdownReq(shardRegion: ActorRef) extends CoordinatorCommand

    // DomainEvents for the persistent state of the event sourced ShardCoordinator
    sealed trait DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionRegistered(region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionProxyRegistered(regionProxy: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionTerminated(region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionProxyTerminated(regionProxy: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardHomeAllocated(shard: ShardId, region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardHomeDeallocated(shard: ShardId) extends DomainEvent

    object State {
      val empty = State()
    }

    /**
     * Persistent state of the event sourced ShardCoordinator.
     */
    @SerialVersionUID(1L) final case class State private (
      // region for each shard
      shards: Map[ShardId, ActorRef] = Map.empty,
      // shards for each region
      regions: Map[ActorRef, Vector[ShardId]] = Map.empty,
      regionProxies: Set[ActorRef] = Set.empty,
      unallocatedShards: Set[ShardId] = Set.empty) {

      def updated(event: DomainEvent): State = event match {
        case ShardRegionRegistered(region) ⇒
          require(!regions.contains(region), s"Region $region already registered: $this")
          copy(regions = regions.updated(region, Vector.empty))
        case ShardRegionProxyRegistered(proxy) ⇒
          require(!regionProxies.contains(proxy), s"Region proxy $proxy already registered: $this")
          copy(regionProxies = regionProxies + proxy)
        case ShardRegionTerminated(region) ⇒
          require(regions.contains(region), s"Terminated region $region not registered: $this")
          copy(
            regions = regions - region,
            shards = shards -- regions(region),
            unallocatedShards = unallocatedShards ++ regions(region))
        case ShardRegionProxyTerminated(proxy) ⇒
          require(regionProxies.contains(proxy), s"Terminated region proxy $proxy not registered: $this")
          copy(regionProxies = regionProxies - proxy)
        case ShardHomeAllocated(shard, region) ⇒
          require(regions.contains(region), s"Region $region not registered: $this")
          require(!shards.contains(shard), s"Shard [$shard] already allocated: $this")
          copy(
            shards = shards.updated(shard, region),
            regions = regions.updated(region, regions(region) :+ shard),
            unallocatedShards = unallocatedShards - shard)
        case ShardHomeDeallocated(shard) ⇒
          require(shards.contains(shard), s"Shard [$shard] not allocated: $this")
          val region = shards(shard)
          require(regions.contains(region), s"Region $region for shard [$shard] not registered: $this")
          copy(
            shards = shards - shard,
            regions = regions.updated(region, regions(region).filterNot(_ == shard)),
            unallocatedShards = unallocatedShards + shard)
      }
    }

  }

  /**
   * Periodic message to trigger rebalance
   */
  private case object RebalanceTick
  /**
   * End of rebalance process performed by [[RebalanceWorker]]
   */
  private final case class RebalanceDone(shard: ShardId, ok: Boolean)
  /**
   * Check if we've received a shard start request
   */
  private final case class ResendShardHost(shard: ShardId, region: ActorRef)

  private final case class DelayedShardRegionTerminated(region: ActorRef)

  /**
   * INTERNAL API. Rebalancing process is performed by this actor.
   * It sends `BeginHandOff` to all `ShardRegion` actors followed by
   * `HandOff` to the `ShardRegion` responsible for the shard.
   * When the handoff is completed it sends [[RebalanceDone]] to its
   * parent `ShardCoordinator`. If the process takes longer than the
   * `handOffTimeout` it also sends [[RebalanceDone]].
   */
  private[akka] class RebalanceWorker(shard: String, from: ActorRef, handOffTimeout: FiniteDuration,
                                      regions: Set[ActorRef]) extends Actor {
    import Internal._
    regions.foreach(_ ! BeginHandOff(shard))
    var remaining = regions

    import context.dispatcher
    context.system.scheduler.scheduleOnce(handOffTimeout, self, ReceiveTimeout)

    def receive = {
      case BeginHandOffAck(`shard`) ⇒
        remaining -= sender()
        if (remaining.isEmpty) {
          from ! HandOff(shard)
          context.become(stoppingShard, discardOld = true)
        }
      case ReceiveTimeout ⇒ done(ok = false)
    }

    def stoppingShard: Receive = {
      case ShardStopped(shard) ⇒ done(ok = true)
      case ReceiveTimeout      ⇒ done(ok = false)
    }

    def done(ok: Boolean): Unit = {
      context.parent ! RebalanceDone(shard, ok)
      context.stop(self)
    }
  }

  private[akka] def rebalanceWorkerProps(shard: String, from: ActorRef, handOffTimeout: FiniteDuration,
                                         regions: Set[ActorRef]): Props =
    Props(new RebalanceWorker(shard, from, handOffTimeout, regions))

}

/**
 * Singleton coordinator that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class ShardCoordinator(typeName: String, settings: ClusterShardingSettings,
                       allocationStrategy: ShardCoordinator.ShardAllocationStrategy)
  extends PersistentActor with ActorLogging {
  import ShardCoordinator._
  import ShardCoordinator.Internal._
  import ShardRegion.ShardId
  import settings.tuningParameters._

  override def persistenceId = s"/sharding/${typeName}Coordinator"

  override def journalPluginId: String = settings.journalPluginId

  override def snapshotPluginId: String = settings.snapshotPluginId

  val removalMargin = Cluster(context.system).settings.DownRemovalMargin

  var persistentState = State.empty
  var rebalanceInProgress = Set.empty[ShardId]
  var unAckedHostShards = Map.empty[ShardId, Cancellable]
  // regions that have requested handoff, for graceful shutdown
  var gracefulShutdownInProgress = Set.empty[ActorRef]
  var aliveRegions = Set.empty[ActorRef]
  var persistCount = 0

  import context.dispatcher
  val rebalanceTask = context.system.scheduler.schedule(rebalanceInterval, rebalanceInterval, self, RebalanceTick)

  Cluster(context.system).subscribe(self, ClusterShuttingDown.getClass)

  override def postStop(): Unit = {
    super.postStop()
    rebalanceTask.cancel()
    Cluster(context.system).unsubscribe(self)
  }

  override def receiveRecover: Receive = {
    case evt: DomainEvent ⇒
      log.debug("receiveRecover {}", evt)
      evt match {
        case ShardRegionRegistered(region) ⇒
          persistentState = persistentState.updated(evt)
        case ShardRegionProxyRegistered(proxy) ⇒
          persistentState = persistentState.updated(evt)
        case ShardRegionTerminated(region) ⇒
          if (persistentState.regions.contains(region))
            persistentState = persistentState.updated(evt)
          else {
            log.debug("ShardRegionTerminated, but region {} was not registered. This inconsistency is due to that " +
              " some stored ActorRef in Akka v2.3.0 and v2.3.1 did not contain full address information. It will be " +
              "removed by later watch.", region)
          }
        case ShardRegionProxyTerminated(proxy) ⇒
          if (persistentState.regionProxies.contains(proxy))
            persistentState = persistentState.updated(evt)
        case ShardHomeAllocated(shard, region) ⇒
          persistentState = persistentState.updated(evt)
        case _: ShardHomeDeallocated ⇒
          persistentState = persistentState.updated(evt)
      }

    case SnapshotOffer(_, state: State) ⇒
      log.debug("receiveRecover SnapshotOffer {}", state)
      //Old versions of the state object may not have unallocatedShard set,
      // thus it will be null.
      if (state.unallocatedShards == null)
        persistentState = state.copy(unallocatedShards = Set.empty)
      else
        persistentState = state

    case RecoveryCompleted ⇒
      persistentState.regionProxies.foreach(context.watch)
      persistentState.regions.foreach { case (a, _) ⇒ context.watch(a) }
      persistentState.shards.foreach { case (a, r) ⇒ sendHostShardMsg(a, r) }
      allocateShardHomes()
  }

  override def receiveCommand: Receive = {
    case Register(region) ⇒
      log.debug("ShardRegion registered: [{}]", region)
      aliveRegions += region
      if (persistentState.regions.contains(region))
        sender() ! RegisterAck(self)
      else {
        gracefulShutdownInProgress -= region
        saveSnapshotWhenNeeded()
        persist(ShardRegionRegistered(region)) { evt ⇒
          val firstRegion = persistentState.regions.isEmpty

          persistentState = persistentState.updated(evt)
          context.watch(region)
          sender() ! RegisterAck(self)

          if (firstRegion)
            allocateShardHomes()
        }
      }

    case RegisterProxy(proxy) ⇒
      log.debug("ShardRegion proxy registered: [{}]", proxy)
      if (persistentState.regionProxies.contains(proxy))
        sender() ! RegisterAck(self)
      else {
        saveSnapshotWhenNeeded()
        persist(ShardRegionProxyRegistered(proxy)) { evt ⇒
          persistentState = persistentState.updated(evt)
          context.watch(proxy)
          sender() ! RegisterAck(self)
        }
      }

    case t @ Terminated(ref) ⇒
      if (persistentState.regions.contains(ref)) {
        if (removalMargin != Duration.Zero && t.addressTerminated && aliveRegions(ref))
          context.system.scheduler.scheduleOnce(removalMargin, self, DelayedShardRegionTerminated(ref))
        else
          regionTerminated(ref)
      } else if (persistentState.regionProxies.contains(ref)) {
        log.debug("ShardRegion proxy terminated: [{}]", ref)
        saveSnapshotWhenNeeded()
        persist(ShardRegionProxyTerminated(ref)) { evt ⇒
          persistentState = persistentState.updated(evt)
        }
      }

    case DelayedShardRegionTerminated(ref) ⇒
      regionTerminated(ref)

    case GetShardHome(shard) ⇒
      if (!rebalanceInProgress.contains(shard)) {
        persistentState.shards.get(shard) match {
          case Some(ref) ⇒ sender() ! ShardHome(shard, ref)
          case None ⇒
            val activeRegions = persistentState.regions -- gracefulShutdownInProgress
            if (activeRegions.nonEmpty) {
              val getShardHomeSender = sender()
              val regionFuture = allocationStrategy.allocateShard(getShardHomeSender, shard, activeRegions)
              regionFuture.value match {
                case Some(Success(region)) ⇒
                  continueGetShardHome(shard, region, getShardHomeSender)
                case _ ⇒
                  // continue when future is completed
                  regionFuture.map { region ⇒
                    AllocateShardResult(shard, Some(region), getShardHomeSender)
                  }.recover {
                    case _ ⇒ AllocateShardResult(shard, None, getShardHomeSender)
                  }.pipeTo(self)
              }
            }
        }
      }

    case AllocateShardResult(shard, None, getShardHomeSender) ⇒
      log.debug("Shard [{}] allocation failed. It will be retried.", shard)

    case AllocateShardResult(shard, Some(region), getShardHomeSender) ⇒
      continueGetShardHome(shard, region, getShardHomeSender)

    case ShardStarted(shard) ⇒
      unAckedHostShards.get(shard) match {
        case Some(cancel) ⇒
          cancel.cancel()
          unAckedHostShards = unAckedHostShards - shard
        case _ ⇒
      }

    case ResendShardHost(shard, region) ⇒
      persistentState.shards.get(shard) match {
        case Some(`region`) ⇒ sendHostShardMsg(shard, region)
        case _              ⇒ //Reallocated to another region
      }

    case RebalanceTick ⇒
      if (persistentState.regions.nonEmpty) {
        val shardsFuture = allocationStrategy.rebalance(persistentState.regions, rebalanceInProgress)
        shardsFuture.value match {
          case Some(Success(shards)) ⇒
            continueRebalance(shards)
          case _ ⇒
            // continue when future is completed
            shardsFuture.map { shards ⇒ RebalanceResult(shards)
            }.recover {
              case _ ⇒ RebalanceResult(Set.empty)
            }.pipeTo(self)
        }
      }

    case RebalanceResult(shards) ⇒
      continueRebalance(shards)

    case RebalanceDone(shard, ok) ⇒
      rebalanceInProgress -= shard
      log.debug("Rebalance shard [{}] done [{}]", shard, ok)
      // The shard could have been removed by ShardRegionTerminated
      if (persistentState.shards.contains(shard))
        if (ok) {
          saveSnapshotWhenNeeded()
          persist(ShardHomeDeallocated(shard)) { evt ⇒
            persistentState = persistentState.updated(evt)
            log.debug("Shard [{}] deallocated", evt.shard)
            allocateShardHomes()
          }
        } else // rebalance not completed, graceful shutdown will be retried
          gracefulShutdownInProgress -= persistentState.shards(shard)

    case GracefulShutdownReq(region) ⇒
      if (!gracefulShutdownInProgress(region))
        persistentState.regions.get(region) match {
          case Some(shards) ⇒
            log.debug("Graceful shutdown of region [{}] with shards [{}]", region, shards)
            gracefulShutdownInProgress += region
            continueRebalance(shards.toSet)
          case None ⇒
        }

    case SaveSnapshotSuccess(_) ⇒
      log.debug("Persistent snapshot saved successfully")

    case SaveSnapshotFailure(_, reason) ⇒
      log.warning("Persistent snapshot failure: {}", reason.getMessage)

    case ShardHome(_, _) ⇒
    //On rebalance, we send ourselves a GetShardHome message to reallocate a
    // shard. This receive handles the "response" from that message. i.e. ignores it.

    case ClusterShuttingDown ⇒
      log.debug("Shutting down ShardCoordinator")
      // can't stop because supervisor will start it again,
      // it will soon be stopped when singleton is stopped
      context.become(shuttingDown)

    case ShardRegion.GetCurrentRegions ⇒
      val reply = ShardRegion.CurrentRegions(persistentState.regions.keySet.map { ref ⇒
        if (ref.path.address.host.isEmpty) Cluster(context.system).selfAddress
        else ref.path.address
      })
      sender() ! reply

    case _: CurrentClusterState ⇒
  }

  def regionTerminated(ref: ActorRef): Unit =
    if (persistentState.regions.contains(ref)) {
      log.debug("ShardRegion terminated: [{}]", ref)
      persistentState.regions(ref).foreach { s ⇒ self ! GetShardHome(s) }

      gracefulShutdownInProgress -= ref

      saveSnapshotWhenNeeded()
      persist(ShardRegionTerminated(ref)) { evt ⇒
        persistentState = persistentState.updated(evt)
        allocateShardHomes()
      }
    }

  def shuttingDown: Receive = {
    case _ ⇒ // ignore all
  }

  def saveSnapshotWhenNeeded(): Unit = {
    persistCount += 1
    if (persistCount % snapshotAfter == 0) {
      log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
      saveSnapshot(persistentState)
    }
  }

  def sendHostShardMsg(shard: ShardId, region: ActorRef): Unit = {
    region ! HostShard(shard)
    val cancel = context.system.scheduler.scheduleOnce(shardStartTimeout, self, ResendShardHost(shard, region))
    unAckedHostShards = unAckedHostShards.updated(shard, cancel)
  }

  def allocateShardHomes(): Unit = persistentState.unallocatedShards.foreach { self ! GetShardHome(_) }

  def continueGetShardHome(shard: ShardId, region: ActorRef, getShardHomeSender: ActorRef): Unit =
    if (!rebalanceInProgress.contains(shard)) {
      persistentState.shards.get(shard) match {
        case Some(ref) ⇒ getShardHomeSender ! ShardHome(shard, ref)
        case None ⇒
          if (persistentState.regions.contains(region) && !gracefulShutdownInProgress.contains(region)) {
            saveSnapshotWhenNeeded()
            persist(ShardHomeAllocated(shard, region)) { evt ⇒
              persistentState = persistentState.updated(evt)
              log.debug("Shard [{}] allocated at [{}]", evt.shard, evt.region)

              sendHostShardMsg(evt.shard, evt.region)
              getShardHomeSender ! ShardHome(evt.shard, evt.region)
            }
          } else
            log.debug("Allocated region {} for shard [{}] is not (any longer) one of the registered regions: {}",
              region, shard, persistentState)
      }
    }

  def continueRebalance(shards: Set[ShardId]): Unit =
    shards.foreach { shard ⇒
      if (!rebalanceInProgress(shard)) {
        persistentState.shards.get(shard) match {
          case Some(rebalanceFromRegion) ⇒
            rebalanceInProgress += shard
            log.debug("Rebalance shard [{}] from [{}]", shard, rebalanceFromRegion)
            context.actorOf(rebalanceWorkerProps(shard, rebalanceFromRegion, handOffTimeout,
              persistentState.regions.keySet ++ persistentState.regionProxies))
          case None ⇒
            log.debug("Rebalance of non-existing shard [{}] is ignored", shard)
        }

      }
    }

}
