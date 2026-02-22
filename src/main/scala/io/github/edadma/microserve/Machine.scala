package io.github.edadma.microserve

abstract class Machine:
  val start: State

  class PseudoState extends State { def on = _ => () }

  case object INITIAL extends PseudoState
  case object FINAL extends PseudoState

  var received: Int = 0
  var state: State = INITIAL
  var trace = false

  def isFinal: Boolean = state == FINAL

  def reset(): Unit =
    state = INITIAL
    full = false
    received = 0

  var pushedback: Int = 0
  var full = false

  def pushback(a: Int): Unit =
    if full then sys.error(s"pushback buffer full: $pushedback")
    full = true
    pushedback = a

  protected def goto(next: State): Unit =
    val prev = state
    next.enter()
    if state == prev then state = next

  def selfTransition(): Unit = transition(state)

  def transition(next: State): Unit =
    if trace then println(s"$state => $next")
    state.exit()
    goto(next)

  def directTransition(next: State): Unit =
    if trace then println(s"$state =direct> $next")
    goto(next)

  def send(a: Int): Unit =
    def send(a: Int): Unit =
      if trace then
        println(s"$state <- $a (${if a == '\r' then "\\r" else if a == '\n' then "\\n" else a.toChar.toString})")

      if !state.on.isDefinedAt(a) then sys.error(s"state $state not defined at value $a")
      state.on(a)

    if state == INITIAL then transition(start)
    received += 1
    send(a)

    while full do
      full = false
      send(pushedback)
  end send

  abstract class State:
    def on: PartialFunction[Int, Unit]

    def enter(): Unit = {}
    def exit(): Unit = {}
  end State

  override def toString: String = s"machine state: $state, received: $received"
end Machine
