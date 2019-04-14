package game.mode

import actors.PlayerWithActor
import common.{Resources, Util}
import controllers._
import game.Gameboard
import game.mode.GameMode._
import game.state.TurnState._
import game.state.{GameState, PlayerState, TurnState}
import models.{Army, NeutralPlayer, OwnedArmy, Player}
import play.api.Logger

import scala.util.Random

/**
  * Concrete implementation of GameMode bound for DI at runtime. Defines the rules
  * for the Skirmish mode from GOT Risk. See
  * [[https://theop.games/wp-content/uploads/2019/02/got_risk_rules.pdf]] for the rules
  */
class SkirmishGameMode extends GameMode {
  type StateType = GameState
  /** GameMode-specific gameboard, loaded through Resource injection */
  lazy override val gameboard: Gameboard = Resources.SkirmishGameboard

  override def assignTurnOrder(players: Seq[PlayerWithActor]): Seq[PlayerWithActor] =
    Random.shuffle(players)

  override def initializeGameState(callback: Callback)(implicit state: GameState): Unit = {
    // Assign territories to players
    val perTerritory = Resources.SkirmishInitialArmy
    val territoryIndices = Util.listBuffer(Random.shuffle(state.boardState.indices.toList))
    val firstPlayer = state.turnOrder.head.player
    calculateAllocations(state).zipWithIndex.foreach { case (allocation, index) =>
      // Sample and then remove from remaining territories
      val territories = territoryIndices.take(allocation)
      territoryIndices --= territories
      // Add OwnedArmy's to each of the sampled territories
      territories.foreach { i =>
        val army = OwnedArmy(Army(perTerritory), state.turnOrder(index).player)
        state.boardState.update(i, Some(army))
      }
      // Update the total army count for the player and assign turn states
      val player = state.turnOrder(index).player
      state.playerStates.update(index, PlayerState(player, Army(allocation * perTerritory),
        if (player == firstPlayer) reinforcement(player) else TurnState(Idle)))
    }
    callback.broadcast(UpdateBoardState(state), None)
  }

  /**
    * Utility method that creates a reinforcement state machine object and
    * calculates the reinforcement allocation as necessary
    * @param player The player to use to calculate reinforcements
    * @param state The GameState context object
    * @return A new TurnState object for Reinforcement State containing the
    *         calculated allocation
    */
  def reinforcement(player: Player)(implicit state: GameState): TurnState =
    TurnState(Reinforcement, "amount" -> calculateReinforcement(player))

  /**
    * Performs the calculation logic according to values injected from Resources
    * for the target player
    * @param player The player to calculate reinforcements for
    * @param state The GameState context object
    * @return The number of reinforcements the player should receive, as an Int
    */
  def calculateReinforcement(player: Player)(implicit state: GameState): Int = {
    val conquered = state.boardState.zipWithIndex
      .filter { case (oaOption, _) => oaOption.isDefined && oaOption.get.owner == player }
    val territories = conquered.length
    val castles = conquered.count { case (_, index) => gameboard.nodes(index).dto.hasCastle }
    val base = Resources.SkirmishReinforcementBase
    val divisor = Resources.SkirmishReinforcementDivisor
    Math.max(Math.floor((territories + castles) / divisor.toDouble), base.toDouble).toInt
  }

  /**
    * Calculates initial allocations for all players in the game (by turn order),
    * equally dividing territories randomly between each player and giving the
    * remainder, if any, equally to the last players
    * @param state The GameState context object
    * @return A list giving the number of army tokens each player should receive,
    *         ordered by the turn order
    */
  def calculateAllocations(implicit state: GameState): Seq[Int] = {
    val base = state.boardState.length / state.gameSize
    val remainder = state.boardState.length % state.gameSize
    if (remainder == 0) {
      List.fill(state.gameSize)(base)
    } else {
      // Give equal amounts to each player (base), while distributing the
      // remainder equally to the last players by turn order
      (0 until state.gameSize)
        .map(i => if (i >= state.gameSize - remainder) base + 1 else base)
    }
  }

  override def handlePacket(packet: InGamePacket, callback: Callback)
                           (implicit state: GameState): Unit = {
    state.turnOrder.find(a => a.id == packet.playerId).foreach { player =>
      packet match {
        case RequestPlaceReinforcements(_, _, assignments) =>
          requestPlaceReinforcements(callback, player, assignments)
        case RequestAttack(_, _, attack) =>
          requestAttack(callback, player, attack)
        case RequestEndTurn(_, _) =>
          requestEndTurn(callback, player)
      }
    }
  }


  /**
    * Handles incoming request place reinforcements packet. Validates the
    * request and then sends a RequestReply depending on whether the request
    * gets approved. If it is approved, adjust game state as necessary and
    * move the turn state's state machine forwards
    *
    * @param callback The Callback object providing a means of sending outgoing
    *                 packets to either the entire lobby or to one player
    * @param actor The player that initiated the request
    * @param assignments The proposed assignments Seq[(territory index -> amount)]
    * @param state The GameState context object
    */
  def requestPlaceReinforcements(callback: GameMode.Callback, actor: PlayerWithActor,
                                 assignments: Seq[(Int, Int)])
                                (implicit state: GameState): Unit = {
    val logger = Logger(this.getClass).logger
    logger.error("henlo")
    if (validateReinforcements(callback, actor, assignments)) {
      callback.send(RequestReply(RequestResponse.Accepted), actor.id)
      assignments.foreach(tuple => {
        val index = tuple._1
        val increment = tuple._2
        val oldArmy = state.boardState(index)
        oldArmy.foreach(army => state.boardState(index) =
          Some(OwnedArmy(army.army += increment, army.owner)))
      })
      val total = assignments.map(_._2).sum
      val oldState = state.stateOf(actor.player)
      oldState.foreach(ps => state(actor.player) =
        PlayerState(actor.player, ps.units += total, ps.turnState))
      state.advanceTurnState(None)
      callback.broadcast(UpdateBoardState(state), None)
      callback.broadcast(UpdatePlayerState(state), None)
    }
  }

  /**
    * Handles incoming request attack packet. Validates the
    * request and then sends a RequestReply depending on whether the request
    * gets approved. If it is approved, adjust game state as necessary and
    * move the turn state's state machine forwards
    *
    * @param callback The Callback object providing a means of sending outgoing
    *                 packets to either the entire lobby or to one player
    * @param actor The player that initiated the request
    * @param attack The proposed attack Seq[1st territory index, 2nd territory index, attack amount]
    * @param state The GameState context object
    */
  def requestAttack(callback: GameMode.Callback, actor: PlayerWithActor,
                    attack: Seq[Int])
                   (implicit state: GameState): Unit = {
    if (validateAttack(callback, actor, attack)) {
      callback.send(RequestReply(RequestResponse.Accepted), actor.id)
      val attackingIndex = attack.head
      val defendingIndex = attack.tail.head
      val attackAmount = attack.tail.tail.head
      val defendingPlayer = state.boardState(defendingIndex).get.owner
      defendingPlayer match {
        case _: NeutralPlayer => {
          //TODO: perform the attack and send results
        }
        case _ => state.currentAttack = Some (Seq (attackingIndex, defendingIndex, attackAmount) )
      }
      callback.broadcast (UpdateBoardState (state), None)
      callback.broadcast (UpdatePlayerState (state), None)
    }
  }

  /**
    * Handles incoming packet with defense amounts, coming from the defending player.
    * Validates the packet and then proceeds to calculate the attack, mutate armies
    * and territories as necessary, and send the result.
    *
    * @param callback The Callback object providing a means of sending outgoing
    *                 packets to either the entire lobby or to one player
    * @param actor The player that initiated the request
    * @param attack The proposed attack Seq[1st territory index, 2nd territory index, attack amount]
    * @param state The GameState context object
    */
  def defenseResponse(callback: GameMode.Callback, actor: PlayerWithActor,
                    attack: Seq[Int])
                   (implicit state: GameState): Unit = {
    //TODO: behavior
  }


  //TODO: specialized method for generating results of an attack, in case a neutral player is being attacked

  def requestEndTurn(callback: GameMode.Callback, actor: PlayerWithActor,
                     )
                     (implicit state: GameState): Unit = {
    val logger = Logger(this.getClass).logger
    logger.error("hello")
    logger.error(state.turn.toString)
    state.advanceTurnState(None)
    state.advanceTurnState(None)
    logger.error(state.turn.toString)
    callback.broadcast(UpdateBoardState(state), None)
    callback.broadcast(UpdatePlayerState(state), None)
  }

  /**
    * Validates the reinforcement request given by the player
    *
    * @param callback The Callback object providing a means of sending outgoing
    *                 packets to either the entire lobby or to one player
    * @param actor The player that initiated the request
    * @param assignments The proposed assignments Seq[(territory index -> amount)]
    * @param state The GameState context object
    * @return
    */
  def validateReinforcements(callback: GameMode.Callback, actor: PlayerWithActor,
                             assignments: Seq[(Int, Int)])
                            (implicit state: GameState): Boolean = {
    val calculated = calculateReinforcement(actor.player)
    val totalPlaced = assignments.map(tup => tup._2).sum
    val invalidAssignment = assignments.exists(
      assignment => state.boardState(assignment._1).exists(army => army.owner != actor.player)
    )

    if (state.isInState(actor.player, TurnState.Reinforcement)) {
      if (invalidAssignment) {
        callback.send(RequestReply(RequestResponse.Rejected,
          s"Invalid territory placement; either a selected territory is undefined" +
            s" or that player does not own one of the territories."
        ), actor.id)
        false
      } else if (totalPlaced == calculated) {
        // Valid
        true
      } else {
        // Invalid
        val descriptor = if (calculated > totalPlaced) "many" else "few"
        callback.send(RequestReply(RequestResponse.Rejected, s"Too $descriptor " +
          s"reinforcements in attempted placement $totalPlaced for " +
          s"allocation $calculated"), actor.id)
        false
      }
    } else {
      callback.send(RequestReply(RequestResponse.Rejected, "Invalid state to " +
        "place reinforcements"), actor.id)
      false
    }
  }

  /**
    * Validates the attack request given by the player
    *
    * @param callback The Callback object providing a means of sending outgoing
    *                 packets to either the entire lobby or to one player
    * @param actor The player that initiated the request
    * @param attack The proposed attack Seq[1st territory index, 2nd territory index, attack amount]
    *               1st territory is attacking, 2nd territory is defending
    * @param state The GameState context object
    * @return
    */
  def validateAttack(callback: GameMode.Callback, actor: PlayerWithActor,
                     attack: Seq[Int])
                            (implicit state: GameState): Boolean = {
    if (attack.length != 3) {
      callback.send(RequestReply(RequestResponse.Rejected,
        s"Invalid attack request; attack must be an array of 3 integers"
      ), actor.id)
      false
    } else if (state.currentPlayer != actor.player) {
      callback.send(RequestReply(RequestResponse.Rejected,
        s"Invalid attack request; it is not that player's attacking turn"
      ), actor.id)
      false
    } else {
      val attackingIndex = attack.head
      val defendingIndex = attack.tail.head
      val attackAmount = attack.tail.tail.head
      val invalidOwner = state.boardState(attackingIndex).fold(true)(
        armyWithOwner => armyWithOwner.owner != actor.player
      )
      val validAttack = gameboard.nodes(attackingIndex).dto.connections.contains(defendingIndex)
      val invalidAmount = state.boardState(attackingIndex).fold(true){
        armyWithOwner => attackAmount >= armyWithOwner.army.size
      } || attackAmount < 1
      if (invalidOwner) {
        callback.send(RequestReply(RequestResponse.Rejected,
          s"Invalid attack request; either the attacking territory could not be found"
          + " or the current player does not own that territory."
        ), actor.id)
        false
      } else if (!validAttack) {
        callback.send(RequestReply(RequestResponse.Rejected,
          s"Invalid attack request; the defending territory is not adjacent"
          + " to the attacking territory."
        ), actor.id)
        false
      } else if (invalidAmount) {
        callback.send(RequestReply(RequestResponse.Rejected,
          s"Invalid attack request; the attacking troop amount must be non-zero and lower"
          + " than the troop amount in the attacking territory"
        ), actor.id)
        false
      } else {
        //Valid
        true
      }
    }
  }

  override def playerDisconnect(actor: PlayerWithActor, callback: Callback)
                               (implicit state: GameState): Unit = {

    //TODO: handle disconnecting when a player is in an attack state, or
    //TODO: when a player is in a defending state, likely regardless of
    //TODO: whether or not that player actually has the current turn

    // This solution assumes that playerStates *hasn't* removed the player yet
    if (state.currentPlayer == actor.player) {
      state.advanceTurn()
    }
    // We assume that prevTurnState is *always* TurnState.Idle
    val newTurnState = state.stateOf(state.currentPlayer).get.turnState.advanceState
    state(state.currentPlayer) = state.constructPlayerState(state.currentPlayer, newTurnState)

    // Release all owned territories
    state.boardState.zipWithIndex
      .filter { case (armyOption, _) => armyOption.forall(oa => oa.owner == actor.player) }
      .foreach {
        case (ownedArmyOption, index) => ownedArmyOption.foreach {
          ownedArmy => state.boardState.update(index, Some(OwnedArmy(ownedArmy.army, NeutralPlayer())))
        }
      }
    // Remove from turn order
    state.turnOrder = Util.remove(actor, state.turnOrder)
    // Notify game of changes (no need to send to the disconnecting player)
    callback.broadcast(UpdateBoardState(state), Some(actor.id))
    callback.broadcast(UpdatePlayerState(state), Some(actor.id))
  }

  /**
    * Subclass hook that allows subclasses to register custom GameState implementations
    * to be used when initializing games.
    * @param turnOrder The turn order of the game (sent from the Game actor)
    * @return A GameState object
    */
  override def makeGameState(turnOrder: Seq[PlayerWithActor]): GameState = {
    val seq = IndexedSeq() ++ turnOrder
    new GameState(seq, gameboard.nodeCount)
  }
}
