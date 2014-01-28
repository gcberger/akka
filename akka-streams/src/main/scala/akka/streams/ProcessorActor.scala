package akka.streams

import rx.async.api.Producer
import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

object ProcessorActor {
  // TODO: needs settings
  //def processor[I, O](operations: Operation[I, O]): Processor[I, O] = ???

  /* The result of a calculation step */
  sealed trait Result[+O] {
    def ~[O2 >: O](other: Result[O2]): Result[O2] =
      if (other == Continue) this
      else Combine(this, other)
  }
  sealed trait SimpleResult[+O] extends Result[O]
  sealed trait ForwardResult[+O] extends SimpleResult[O]
  sealed trait BackchannelResult extends SimpleResult[Nothing]

  // SEVERAL RESULTS
  case class Combine[O](first: Result[O], second: Result[O]) extends Result[O]
  // TODO: consider introducing a `ForwardCombine` type tagging purely
  //       forward going combinations to avoid the stepper for simple
  //       operation combinations

  // NOOP
  case object Continue extends Result[Nothing] {
    override def ~[O2 >: Nothing](other: Result[O2]): Result[O2] = other
  }
  // BI-DIRECTIONAL: needs to be wired in both directions
  type PublisherDefinition[O] = SubscriptionResults ⇒ PublisherResults[O] ⇒ PublisherHandler[O]
  case class EmitProducer[O](f: PublisherDefinition[O]) extends SimpleResult[Producer[O]]
  case class EmitProducerFinished[O](f: PublisherResults[O] ⇒ PublisherHandler[O]) extends ForwardResult[Producer[O]]

  // FORWARD
  case class Emit[+O](t: O) extends ForwardResult[O]
  case class EmitMany[+O](elements: Vector[O]) extends ForwardResult[O] {
    require(elements.size > 1, "don't use EmitMany to emit only one element")
  }
  case object Complete extends ForwardResult[Nothing]
  case class Error(cause: Throwable) extends ForwardResult[Nothing]

  case class Subscribe[T, U](producer: Producer[T])(handler: SubscriptionResults ⇒ SubscriptionHandler[T, U]) extends ForwardResult[U]

  // BACKCHANNEL
  case class RequestMore(n: Int) extends BackchannelResult

  // CUSTOM
  private[streams] trait SideEffect[+O] extends Result[O] {
    def run(): Result[O]
  }

  trait SubscriptionHandler[-I, +O] {
    def handle(result: ForwardResult[I]): Result[O]
  }
  trait SimpleSubscriptionHandler[-I, +O] extends SubscriptionHandler[I, O] {
    def handle(result: ForwardResult[I]): Result[O] = result match {
      case Emit(i)      ⇒ onNext(i)
      case EmitMany(it) ⇒ it.map(onNext).reduceLeftOption(_ ~ _).getOrElse(Continue)
      case Complete     ⇒ onComplete()
      case Error(cause) ⇒ onError(cause)
    }

    def onNext(i: I): Result[O]
    def onComplete(): Result[O]
    def onError(cause: Throwable): Result[O]
  }
  trait SubscriptionResults {
    def requestMore(n: Int): Result[Nothing]
  }

  trait PublisherHandler[O] {
    def handle(result: BackchannelResult): Result[Producer[O]]
  }
  /*trait SimplePublisherHandler[+O] extends PublisherHandler[O] {

  }*/
  trait PublisherResults[O] {
    def emit(o: O): Result[Producer[O]]
    def complete: Result[Producer[O]]
    def error(cause: Throwable): Result[Producer[O]]
  }

  trait OpInstance[-I, +O] {
    def handle(result: SimpleResult[I]): Result[O]

    // FIXME: remove later and implement properly everywhere
    // convenience method which calls handle for all elements and combines the results
    // (which obviously isn't as performant than a direct handling
    def handleMany(e: EmitMany[I]): Result[O] =
      e.elements.map(e ⇒ handle(Emit(e))).reduceLeftOption(_ ~ _).getOrElse(Continue)
  }
  @deprecated("Deprecated and broken")
  trait SimpleOpInstance[-I, +O] extends OpInstance[I, O] with SimpleSubscriptionHandler[I, O] {
    def handle(result: SimpleResult[I]): Result[O] = ???
    def requestMoreResult(n: Int): Result[O] = ???

    def handle(result: BackchannelResult): Result[O] = result match {
      case RequestMore(n) ⇒ RequestMore(requestMore(n))
    }
    def requestMore(n: Int): Int
  }

  trait OpInstanceStateMachine[I, O] extends OpInstance[I, O] {
    type State = SimpleResult[I] ⇒ Result[O]
    def initialState: State
    var state: State = initialState

    def handle(result: SimpleResult[I]): Result[O] = state(result)

    def become(newState: State): Unit = state = newState
  }

  def andThenInstance[I1, I2, O](andThen: AndThen[I1, I2, O]): OpInstance[I1, O] =
    new OpInstance[I1, O] {
      val firstI = instantiate(andThen.first)
      val secondI = instantiate(andThen.second)

      def handle(result: SimpleResult[I1]): Result[O] = result match {
        case f: ForwardResult[I1] ⇒ handleFirstResult(firstI.handle(f))
        case b: BackchannelResult ⇒ handleSecondResult(secondI.handle(b))
      }

      sealed trait Calc
      sealed trait SimpleCalc extends Calc
      case class CombinedResult(first: Calc, second: Calc) extends Calc
      case class HalfFinished(first: Result[O], second: Calc) extends Calc

      case class FirstResult(result: Result[I2]) extends SimpleCalc
      case class SecondResult(result: Result[O]) extends SimpleCalc
      case class Finished(result: Result[O]) extends SimpleCalc
      // TODO: add constant for Finished(Continue)

      // these two are short-cutting entry-points that handle common non-trampolining cases
      // before calling the stepper (which must contains the same logic once again)
      // This way we can avoid the wrapping cost of trampolining in many cases.
      def handleFirstResult(res: Result[I2]): Result[O] = res match {
        case Continue             ⇒ Continue
        case b: BackchannelResult ⇒ b
        case Combine(a, b)        ⇒ run(CombinedResult(FirstResult(a), FirstResult(b)))
        case x                    ⇒ run(FirstResult(x))
      }
      def handleSecondResult(res: Result[O]): Result[O] = res match {
        case Continue            ⇒ Continue
        case f: ForwardResult[O] ⇒ f
        case Combine(a, b)       ⇒ run(CombinedResult(SecondResult(a), SecondResult(b)))
        case x                   ⇒ run(SecondResult(x))
      }

      @tailrec def run(calc: Calc): Result[O] = {
        val res = runOneStep(calc)
        //println(s"$calc => $res")
        res match {
          case Finished(res) ⇒ res
          case x             ⇒ run(x)
        }
      }

      def runOneStep(calc: Calc): Calc =
        calc match {
          case SecondResult(Combine(a, b)) ⇒
            //CombinedResult(SecondResult(a), SecondResult(b))
            // inline "case CombinedResult" for one less round-trip
            runOneStep(SecondResult(a)) match {
              case Finished(Continue) ⇒ SecondResult(b)
              case Finished(aRes)     ⇒ HalfFinished(aRes, SecondResult(b))
              case x                  ⇒ CombinedResult(x, SecondResult(b))
            }
          case FirstResult(Combine(a, b)) ⇒
            //CombinedResult(FirstResult(a), FirstResult(b))
            // inline "case CombinedResult" for one less round-trip
            runOneStep(FirstResult(a)) match {
              case Finished(Continue) ⇒ FirstResult(b)
              case Finished(aRes)     ⇒ HalfFinished(aRes, FirstResult(b))
              case x                  ⇒ CombinedResult(x, FirstResult(b))
            }
          case s: SimpleCalc ⇒ runSimpleStep(s)
          case CombinedResult(a, b) ⇒
            runOneStep(a) match {
              case Finished(Continue) ⇒ b
              case Finished(aRes)     ⇒ HalfFinished(aRes, b)
              case x                  ⇒ CombinedResult(x, b)
            }
          case HalfFinished(aRes, b) ⇒
            runOneStep(b) match {
              case Finished(Continue) ⇒ Finished(aRes)
              case Finished(bRes)     ⇒ Finished(aRes ~ bRes)
              case x                  ⇒ HalfFinished(aRes, x)
            }
        }

      def runSimpleStep(calc: SimpleCalc): SimpleCalc = calc match {
        case SecondResult(b: BackchannelResult) ⇒ firstResult(firstI.handle(b))
        case SecondResult(EmitProducer(f)) ⇒
          val rest = f(new SubscriptionResults {
            def requestMore(n: Int): Result[Nothing] = handleSecondResult(RequestMore(n)).asInstanceOf[Result[Nothing]]
          })
          Finished(EmitProducerFinished(rest).asInstanceOf[Result[O]])
        case SecondResult(x: ForwardResult[_]) ⇒ Finished(x)
        case SecondResult(Continue)            ⇒ Finished(Continue)
        case SecondResult(s: SideEffect[_])    ⇒ Finished(s)
        case FirstResult(f: ForwardResult[_])  ⇒ secondResult(secondI.handle(f))
        case FirstResult(r: RequestMore)       ⇒ Finished(r)
        case FirstResult(Continue)             ⇒ Finished(Continue)
        case FirstResult(x: BackchannelResult) ⇒ Finished(x)
      }

      // these are shortcuts like handleFirstResult and handleSecondResult above, but from within the
      // stepper
      def firstResult(res: Result[I2]): SimpleCalc = res match {
        case Continue             ⇒ Finished(Continue)
        case b: BackchannelResult ⇒ Finished(b)
        case x                    ⇒ FirstResult(x)
      }
      def secondResult(res: Result[O]): SimpleCalc = res match {
        case Continue            ⇒ Finished(Continue)
        case f: ForwardResult[O] ⇒ Finished(f)
        case x                   ⇒ SecondResult(x)
      }
    }

  def instantiate[I, O](operation: Operation[I, O]): OpInstance[I, O] = operation match {
    case andThen: AndThen[_, _, _] ⇒ andThenInstance(andThen)
    case Map(f) ⇒
      new OpInstance[I, O] {
        def handle(result: SimpleResult[I]): Result[O] = result match {
          case r: RequestMore ⇒ r
          case Emit(i)        ⇒ Emit(f(i)) // FIXME: error handling
          case EmitMany(is)   ⇒ EmitMany(is.map(f)) // FIXME: error handling
          case Complete       ⇒ Complete
          case e: Error       ⇒ e
        }
      }
    case Fold(seed, acc) ⇒
      new OpInstance[I, O] {
        val batchSize = 10000
        var missing = 0
        var z = seed
        var completed = false
        def requestMore(n: Int): Int = {
          // we can instantly consume all values, even if there's just one requested,
          // though we probably need to be careful with MaxValue which may lead to
          // overflows easily

          missing = batchSize
          batchSize
        }

        def handle(result: SimpleResult[I]): Result[O] = result match {
          case RequestMore(_) ⇒
            missing = batchSize
            RequestMore(batchSize)
          case Emit(i) ⇒
            z = acc(z, i) // FIXME: error handling
            missing -= 1
            maybeRequestMore
          case EmitMany(is) ⇒
            z = is.foldLeft(z)(acc) // FIXME: error handling
            missing -= is.size
            maybeRequestMore
          case Complete ⇒
            if (!completed) {
              completed = true
              Emit(z) ~ Complete
            } else Continue
          case e: Error ⇒ e
        }
        def maybeRequestMore: Result[O] =
          if (missing == 0) {
            missing = batchSize
            RequestMore(batchSize)
          } else Continue
      }
    case Filter(pred) ⇒
      new OpInstance[I, O] {
        def handle(result: SimpleResult[I]): Result[O] = result match {
          case r: RequestMore ⇒ r /* + heuristic of previously filtered? */
          case Emit(i)        ⇒ if (pred(i)) Emit(i.asInstanceOf[O]) else Continue ~ RequestMore(1) // FIXME: error handling
          case e: EmitMany[I] ⇒ handleMany(e)
          case Complete       ⇒ Complete
          case e: Error       ⇒ e
        }
      }
    case FlatMap(f) ⇒
      // two different kinds of flatMap: merge and concat, in RxJava: `flatMap` which merges and `concatMap` which concats
      val shouldMerge = false
      if (shouldMerge) /* merge */
        // we need to subscribe and read the complete stream of inner Producer[O]
        // and then always subscribe and keep one element buffered / requested
        // and then on request deliver those in the sequence they were received
        ???
      else /* concat */
        new OpInstanceStateMachine[I, O] {
          def initialState = Waiting

          def Waiting: State = {
            case RequestMore(n) ⇒
              become(WaitingForElement(n))
              RequestMore(1)
            case Emit(i)  ⇒ throw new IllegalStateException("No element requested")
            case Complete ⇒ Complete
            case e: Error ⇒ e
          }

          def WaitingForElement(remaining: Int): State = {
            case RequestMore(n) ⇒
              become(WaitingForElement(remaining + n))
              RequestMore(0)
            case Emit(i) ⇒
              Subscribe(f(i)) { subscription ⇒ // TODO: handleErrors
                val handler = ReadSubstream(subscription, remaining)
                become(handler)
                handler.subHandler
              }
            case Complete ⇒ Complete
            case e: Error ⇒ e
          }
          case class ReadSubstream(subscription: SubscriptionResults, remaining: Int) extends State {
            // invariant: subscription.requestMore has been called for every
            // of the requested elements
            // each emitted element decrements that counter by one
            var curRemaining = remaining
            var closeAtEnd = false

            override def apply(v1: SimpleResult[I]): Result[O] = v1 match {
              case RequestMore(n) ⇒
                curRemaining += n
                subscription.requestMore(n)
              case Emit(i) ⇒ throw new IllegalStateException("No element requested")
              case Complete ⇒
                closeAtEnd = true; Continue
              case e: Error ⇒
                // shortcut close result stream?
                // also see RxJava `mapManyDelayError`
                ???
            }

            def subHandler = new SubscriptionHandler[O, O] {
              def handle(result: ForwardResult[O]): Result[O] = result match {
                case Emit(i) ⇒
                  curRemaining -= 1
                  Emit(i)
                case Complete ⇒
                  if (curRemaining > 0) {
                    // FIXME: request one more element from parent stream
                    // but how?
                    become(WaitingForElement(curRemaining))
                    RequestMore(1)
                  } else {
                    become(Waiting)
                    Continue
                  }
                case e: Error ⇒ e
              }
            }
          }
        }
    case FoldUntil(seed, acc) ⇒
      new OpInstance[I, O] {
        var z = seed

        def handle(result: SimpleResult[I]): Result[O] = result match {
          case RequestMore(n) ⇒ ??? // TODO: how much to request?
          case Emit(i) ⇒
            acc(z, i) match {
              case FoldResult.Emit(value, nextSeed) ⇒
                z = nextSeed
                Emit(value)
              case FoldResult.Continue(newZ) ⇒
                z = newZ
                Continue
            }
          case Complete ⇒
            // TODO: could also define that the latest seed should be returned, or flag an error
            //       if the last element doesn't match the predicate
            Complete
          case e: Error ⇒ e
        }
      }

    case span: Span[_] ⇒ spanInstance(span)
    case Produce(iterable) ⇒
      new OpInstance[I, O] {
        val it = iterable.iterator

        def handle(result: SimpleResult[I]): Result[O] = result match {
          case RequestMore(n) ⇒ requestMore(n)
        }

        def requestMore(n: Int): Result[O] =
          if (n > 0)
            if (it.hasNext)
              if (n == 1) Emit(it.next()) ~ maybeComplete
              else EmitMany(rec(new VectorBuilder[O], n)) ~ maybeComplete
            else Complete
          else throw new IllegalStateException(s"n = $n is not > 0")

        def maybeComplete =
          if (it.hasNext) Continue else Complete

        @tailrec def rec(result: VectorBuilder[O], remaining: Int): Vector[O] =
          if (remaining > 0 && it.hasNext) rec(result += it.next(), remaining - 1)
          else result.result()
      }
  }

  def spanInstance[I](span: Span[I]): OpInstance[I, Producer[I]] =
    new OpInstance[I, Producer[I]] {
      var curPublisher: PublisherResults[I] = _
      var requested = 0

      def handle(result: SimpleResult[I]): Result[Producer[I]] = result match {
        case RequestMore(n) ⇒
          requested += n
          // FIXME: this condition relies on the fact that the EmitProducer definition is executed before anything
          // else happens. It would be better to introduce a proper state machine here that properly
          // deals with waiting for the callback being called.
          if ((curPublisher eq null) && requested == n) RequestMore(1) else Continue
        case Emit(i) ⇒
          // question is here where to dispatch to and how to access those:
          //  - parent stream
          //  - substream
          val res1 =
            if (curPublisher ne null) curPublisher.emit(i)
            else Continue

          val res2 =
            if (span.pred(i)) {
              val pub = curPublisher
              curPublisher = null
              if (requested > 0) pub.complete ~ RequestMore(1)
              else pub.complete
            } else Continue

          val res3 =
            if ((curPublisher eq null) && requested > 0) {
              requested -= 1
              EmitProducer[I] { requestor ⇒
                publisher ⇒
                  curPublisher = publisher

                  new PublisherHandler[I] {
                    var cachedFirst: AnyRef = i.asInstanceOf[AnyRef]

                    def handle(result: BackchannelResult): Result[Producer[I]] = result match {
                      case RequestMore(n) ⇒
                        if (cachedFirst != null) {
                          val res = curPublisher.emit(cachedFirst.asInstanceOf[I])
                          cachedFirst = null
                          res ~ (if (n > 1) requestor.requestMore(n - 1) else Continue)
                        } else requestor.requestMore(n)
                    }
                  }
              }
            } else Continue

          res1 ~ res2 ~ res3
        case Complete         ⇒ Complete ~ curPublisher.complete
        case e @ Error(cause) ⇒ e ~ curPublisher.error(cause)
      }
    }
}