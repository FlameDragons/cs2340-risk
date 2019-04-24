package game.mode.skirmish

import actors.PlayerWithActor
import common.{Impure, Pure, Resources, Util}
import controllers._
import game.mode.GameMode
import game.mode.skirmish.SkirmishGameContext._
import game.state.{GameState, TurnState}
import game.{GameContext, Gameboard}
import models.Player

/**
  * Concrete implementation of GameMode bound for DI at runtime. Defines the rules
  * for the Skirmish mode from GOT Risk. See
  * [[https://theop.games/wp-content/uploads/2019/02/got_risk_rules.pdf]] for the rules
  */
class SkirmishGameMode extends GameMode {
  /** GameMode-specific gameboard, loaded through Resource injection */
  lazy val gameboard: Gameboard = Resources.SkirmishGameboard

  @Impure.Nondeterministic
  override def hookInitializeGame(implicit context: GameContext): GameContext = {
    import game.mode.skirmish.InitializationHandler._
    val territoryCount = context.state.gameboard.nodeCount
    val allocations = calculateAllocations(territoryCount, context.state.size)
    val territoryAssignments = assignTerritories(allocations, territoryCount)
    context.map(state =>
      state.copy(boardState   = makeBoardState(territoryAssignments),
                 playerStates = makePlayerStates(territoryAssignments)))
  }

  @Impure.Nondeterministic
  override def hookPacket(packet: InGamePacket)(implicit context: GameContext): GameContext =
    ValidationHandler.validate(packet) match {
      case ValidationResult(false, ctx) => ctx
      case ValidationResult(true,  ctx) => ProgressionHandler.handle(packet)(ctx)
    }

  @Pure
  override def hookPlayerDisconnect(actor: PlayerWithActor)
                                   (implicit context: GameContext): GameContext = {
    if (context.state.isInDefense) {




      // TODO implement/refactor
      val currentState = state.stateOf(actor.player).get.turnState
      currentState.state match {
        case TurnState.Defense => {
          //Puts defender in Idle
          state.advanceTurnState(Some(actor.player))
          state.currentAttack = None
        }
        case TurnState.Attack => {
          //Puts defender in Idle
          state.advanceTurnState(Some(state.boardState(state.currentAttack.get.tail.head).get.owner))
          state.currentAttack = None
        }
        case _ =>
      }
    }

    state.modifyTurnAfterDisconnecting(state.turnOrder.indexOf(actor))

    // Release all owned territories
    state.boardState.zipWithIndex
      .filter { case (armyOption, _) => armyOption.forall(oa => oa.owner == actor.player) }
      .foreach {
        case (ownedArmyOption, index) => ownedArmyOption.foreach {
          ownedArmy => state.boardState.update(index, Some(OwnedArmy(ownedArmy.army, Player())))
        }
      }
    // Remove from turn order
    state.turnOrder = Util.remove(actor, state.turnOrder)
    state.clearPayloads()

    if (state.gameSize != 0 && state.stateOf(state.currentPlayer).get.turnState.state == TurnState.Idle) {
      state.advanceTurnState(None, "amount" -> calculateReinforcement(state.currentPlayer))
    }





    // Notify game of changes (no need to send to the disconnecting player)
    context
      .thenBroadcastBoardState(actor.id)
      .thenBroadcastPlayerState(actor.id)
  }

  @Pure
  override def createGameState(turnOrder: IndexedSeq[PlayerWithActor]): GameState =
    GameState(turnOrder, Vector(), Vector(), gameboard)
}