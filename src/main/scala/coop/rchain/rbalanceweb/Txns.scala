package coop.rchain.rbalance.txns
import java.io._
import scala.collection.immutable.Set
import scala.collection.immutable.HashSet
import scala.collection.immutable.Map
import scala.collection.immutable.HashMap

//import scala.concurrent.ExecutionContext.global
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import java.util.concurrent._
import cats.effect._
import io.circe._
//import io.circe.literal._
import org.http4s._
import org.http4s.dsl.io._
import cats.effect._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.client.blaze._
import org.http4s.client._
import coop.rchain.rbalance.transitive._

trait RHOCTxn extends Justified[RHOCTxn] {
  def src       : String
  def trgt      : String
  def amt       : Float
  def hash      : String
  def blockHash : String

  override def toString() : String = {
    s"$src -$amt-> $trgt"
  }
  
}

class InitialRHOCTxn( override val src : String, override val trgt : String, override val amt : Float ) extends RHOCTxn {
//  override def amt           : Float        = 1
  override def hash          : String       = ""
  override def blockHash     : String       = ""
  override def justification : Set[RHOCTxn] = new HashSet[RHOCTxn]()
}
case class RHOCTxnRep( 
  src : String, trgt : String, 
  amt : Float, 
  hash : String, blockHash : String, 
  justification : Set[RHOCTxn] 
) extends RHOCTxn {
  override def equals( a : Any ) = {
    a match {
      case RHOCTxnRep( _, _, _, `hash`, _, _ ) => true
      case _ => false
    }
  }
  override def hashCode = ( hash ).##
}

trait Adjustment extends RHOCTxn {
  def txn                    : RHOCTxn
  def cleanBalance           : Float
  def taintedBalance         : Float

  override def src           : String       = txn.src
  override def trgt          : String       = txn.trgt
  override def amt           : Float        = txn.amt
  override def hash          : String       = txn.hash
  override def blockHash     : String       = txn.blockHash
  override def justification                = txn.justification
}

class InitialAdjustment( 
  val taintAddr : String, val addr : String, override val taintedBalance : Float, override val amt : Float
) extends Adjustment {
  override def txn          = new InitialRHOCTxn( taintAddr, addr, amt )
  override def cleanBalance = 0
}

case class ActualAdjustment( txn : RHOCTxn, cleanBalance : Float, taintedBalance : Float ) extends Adjustment {  
}

trait EdgeT[Node] {
  def src       : Node
  def trgt      : Node
  def weight    : Float
  def hash      : String
  def blockId   : String

  override def toString() : String = {
    s"$src -$weight-> $trgt"
  }
}

object RHOCTxnClosure extends JustifiedClosure[String, RHOCTxn] 
    with InputCSVData {
  import AdjustmentConstants._

  def recordTxn( 
    txn       : RHOCTxn, 
    srcAddr   : String, 
    trgtAddr  : String,
    amt       : Float, 
    hash      : String, 
    blockHash : String
  ) : RHOCTxn = {
    txn match {
      case adj : Adjustment => {
        val clean = getBalance( txn.trgt )        
        val newTxn = new RHOCTxnRep( srcAddr, trgtAddr, amt, hash, blockHash, new HashSet[RHOCTxn]() + txn )
        val taint = getTaint( adj, newTxn )
        new ActualAdjustment( newTxn, clean, taint )
      }
      case irt : RHOCTxn => {
        new RHOCTxnRep( srcAddr, trgtAddr, amt, hash, blockHash, new HashSet[RHOCTxn]() + txn )
      }      
    }
  }

  def provideFeedback( txn : RHOCTxn, rslt : Set[RHOCTxn], lvl : Int ) : Unit = {
    feedback match {
      case lvl2 if lvl2 > 1 => {
        println( s"txn key: ${key(txn)}" )
        println( "Next generation:" )
        rslt.map( { txn => println( txn ) } )
      }
      case lvl1 if lvl1 > 0 => {
        val rsltSize = rslt.size
        println( s"txn key: ${key(txn)}" )
        println( s"Size of next generation:$rsltSize" )
      }
      case _ => {
      }
    }
  }

  def nextRHOCTxnsFromEtherscan( txn : RHOCTxn ) : Set[RHOCTxn] = {
    val lowerCaseAddr = txn.trgt.toLowerCase()
    val rslt = EtherscanAPIAccess.etherscanTxnArray( lowerCaseAddr ).foldLeft( new HashSet[RHOCTxn]() )(
      { ( acc, e ) => {
        EtherscanAPIAccess.txnRecordData( lowerCaseAddr, e ) match {
          case Some( ( srcAddr, trgtAddr, amt, hash, blockHash ) ) => {
            acc + recordTxn( txn, srcAddr, trgtAddr, amt, hash, blockHash )
          }
          case None => {
            acc
          }
        }
      } }
    )

    provideFeedback( txn, rslt, feedback )

    rslt
  }

  def loadAndFormatTxnData( source : String, dir : String ) : List[RHOCTxn] = {
    for( txnArray <- loadTxnData( source, dir ) ) yield {
      RHOCTxnRep(
        txnArray(4),
        txnArray(5),
        txnArray(6).toFloat,
        txnArray(0),
        txnArray(1),
        new HashSet[RHOCTxn]()
      )
    }
  }

  var txnDataV : Option[List[RHOCTxn]] = None
  def txnData() : List[RHOCTxn] = {
    txnDataV match {
      case None => {
        val txnD = loadAndFormatTxnData( txnSource, sourceDir )
        txnDataV = Some( txnD )
        txnD
      }
      case Some( txnD ) => txnD
    }
  }

  def nextRHOCTxnsD( txn : RHOCTxn ) : Set[RHOCTxn] = {
    txnData().filter( ( txnD ) => { txnD.src == txn.trgt } ).map(
      ( t ) => {
        RHOCTxnRep(
          t.src,
          t.trgt,
          t.amt,
          t.hash,
          t.blockHash,
          (new HashSet[RHOCTxn]() + txn)
        )
      }
    ).toSet
  }

  var addressListD : Option[List[String]] = None
  def addressList() : List[String] = {
    addressListD match {
      case None => {
        val addrS = 
          txnData().foldLeft( new HashSet[String]() )(
            ( acc, txn ) => { acc + txn.trgt }
          ).toList
        addressListD = Some( addrS )
        addrS
      }
      case Some( addrS ) => addrS
    }    
  }

  def loadAndFormatWalletData( source : String, dir : String ) : Map[String,Float] = {
    loadWalletData( source, dir ).foldLeft( new HashMap[String,Float]() )(
      ( acc, walletArray ) => {
        acc + ( walletArray( 0 ).toLowerCase -> walletArray( 1 ).toFloat )
      }
    )
  }

  var balancesD : Option[Map[String,Float]] = None
  def balances() : Map[String,Float] = {
    balancesD match {
      case None => {
        val balanceMap = loadAndFormatWalletData( walletSource, sourceDir )
        balancesD = Some( balanceMap )
        balanceMap
      }
      case Some( balanceMap ) => balanceMap
    }
  }

  var taintMapD : Option[Map[String,Set[_ <: RHOCTxn]]] = None
  def theTaintMap() : Map[String,Set[_ <: RHOCTxn]] = {
    taintMapD match {
      case None => {
        val tMD = 
          close( 
            new InitialRHOCTxn( 
              AdjustmentConstants.barcelonaLabel, 
              AdjustmentConstants.barcelonaAddr.toLowerCase, 
              AdjustmentConstants.barcelonaTaint
            )
          )
        taintMapD = Some( tMD )
        tMD
      }
      case Some( tMD ) => { tMD }
    }
  }

  var txnSetD : Option[Set[_ <: RHOCTxn]] = None
  def theTxnSet() : Set[_ <: RHOCTxn] = {
    txnSetD match {
      case None => {
        val txnSet : Set[_ <: RHOCTxn] = 
          txnSetFromClosure( theTaintMap )
        txnSetD = Some( txnSet )
        txnSet
      }
      case Some( tsD ) => { tsD }
    }
  }

  def getBalance( addr : String ) : Float = {
    balances().get( addr ) match {
      case Some( balance ) => balance
        //case None => 0
      case None => 1
    }
  }

  def getSrcPreBalance( txn : RHOCTxn ) : Float = {
    txn match {
      case iTxn : InitialRHOCTxn => AdjustmentConstants.barcelonaTaint
      case rTxn : RHOCTxnRep => {
        txn.justification.toList match {
            case Nil => 1
            case jTxns => {
              val seed : Float = 0
              jTxns.foldLeft( seed )(
                ( acc, jT ) => { acc + jT.asInstanceOf[RHOCTxn].amt }
              )
            }
          }
      }
    }
  }

  def getSrcPostBalance( txn : RHOCTxn ) : Float = {
    val initBal = getSrcPreBalance( txn )
    val debit =
      theTaintMap.get( txn.src ) match {
        case None => 0
        case Some( txns ) => {
          txns.toList match {
            case Nil => 0
            case txnL =>
              val seed : Float = 0
              txnL.foldLeft( seed )(
                ( acc, t ) => { acc + t.asInstanceOf[RHOCTxn].amt }
              )
          }
        }
      }
    initBal - debit
  }

  def getTrgtPreBalance( txn : RHOCTxn ) : Float = {
    txn match {
      case iTxn : InitialRHOCTxn => AdjustmentConstants.barcelonaTaint
      case rTxn : RHOCTxnRep => {
        theTxnSet.filter( ( t ) => t.trgt == txn.trgt ).toList match {
            case Nil => 1
            case jTxns => {
              val seed : Float = 0
              jTxns.foldLeft( seed )(
                ( acc, jT ) => { acc + jT.asInstanceOf[RHOCTxn].amt }
              )
            }
          }
      }
    }
  }

  def getTrgtPostBalance( txn : RHOCTxn ) : Float = {
    val initBal = getTrgtPreBalance( txn )
    val debit =
      theTaintMap.get( txn.trgt ) match {
        case None => 0
        case Some( txns ) => {
          txns.toList match {
            case Nil => 0
            case txnL =>
              val seed : Float = 0
              txnL.foldLeft( seed )(
                ( acc, t ) => { acc + t.asInstanceOf[RHOCTxn].amt }
              )
          }
        }
      }
    initBal - debit
  }

  def getBalance( txn : RHOCTxn ) : Float = {
    balances().get( txn.src ) match { // BUG: we need to get balance at the blockHeight of txn!!!
      case Some( balance ) => {
        if ( balance < txn.amt ) {
          txn.justification.toList match {
            case Nil => 1
            case jTxns => {
              val seed : Float = 0
              jTxns.foldLeft( seed )(
                ( acc, jT ) => { acc + jT.asInstanceOf[RHOCTxn].amt }
              )
            }
          }
        }
        else { balance }
      }
        //case None => 0
      case None => {
        txn.justification.toList match {
          case Nil => 1
          case jTxns => {
            val seed : Float = 0
            jTxns.foldLeft( seed )( 
              ( acc, jT ) => { acc + jT.asInstanceOf[RHOCTxn].amt } 
            )
          }
        }
      }
    }
  }

  def getTaint( adj : Adjustment, txn : RHOCTxn ) : Float = {
    val txnBal = getBalance( txn )
    val normBal = if ( txnBal == 0.0 ) { 1 } else { txnBal }
    ( adj.txn.amt / normBal ) * adj.taintedBalance
  }
  
  def combine( adj : Adjustment, txn : RHOCTxn ) : ActualAdjustment = {
    ActualAdjustment( txn, getBalance( txn.trgt ), getTaint( adj, txn ) )
  }
  def combine( adj : Adjustment, adjNext : Adjustment ) : ActualAdjustment = {
    ActualAdjustment( adjNext.txn, getBalance( adj.txn.trgt ), getTaint( adj, adjNext.txn ) )
  }

  def nextTxnAdjustmentsFromEtherscan( adjustment : Adjustment ) : Set[RHOCTxn] = {
    val txn = adjustment.txn
    val lowerCaseAddr = txn.trgt.toLowerCase()
    val rslt = EtherscanAPIAccess.etherscanTxnArray( lowerCaseAddr ).foldLeft( new HashSet[RHOCTxn]() )(
      { ( acc, e ) => {
        EtherscanAPIAccess.txnRecordData( lowerCaseAddr, e ) match {
          case Some( ( srcAddr, trgtAddr, amt, hash, blockHash ) ) => {
            acc + recordTxn( txn, srcAddr, trgtAddr, amt, hash, blockHash )
          }
          case None => {
            acc
          }
        }
      } }
    )

    provideFeedback( adjustment, rslt, feedback )

    rslt
  }

  def nextTxnAdjustmentsD( adj : Adjustment ) : Set[RHOCTxn] = {
    txnData().filter( ( txnD ) => { txnD.src == adj.trgt } ).map(
      ( t ) => {
        val txnBal = getBalance( adj.src )
        val normBal = if ( txnBal == 0.0 ) { 1 } else { txnBal }
        ActualAdjustment(
          RHOCTxnRep(
            t.src,
            t.trgt,
            t.amt,
            t.hash,
            t.blockHash,
            ( new HashSet[RHOCTxn]() + adj.txn )
          ),
          getBalance( t.trgt ),
          ( t.amt / normBal ) * adj.taintedBalance          
        )
      }
    ).toSet
  }

  def nextTxns( x : RHOCTxn ) : Set[RHOCTxn] = {
    x match {
      case adjustment : Adjustment => nextTxnAdjustmentsD( adjustment )
      case rhocTxn    : RHOCTxn    => nextRHOCTxnsD( rhocTxn )
    }
  }

  override def next = nextTxns
  override def key = _.trgt

  def closeAddr( taintLabel : String, addr : String, taint : Float ) = close( new InitialRHOCTxn( taintLabel, addr, taint ) )

  // Forward calculation
  def computeAdjustment( taintLabel : String, taintAddr : String, taint : Float )( path : List[_ <: RHOCTxn] ) : Adjustment = {
    path match {
      case Nil => new InitialAdjustment( taintLabel, taintAddr, 0, taint )
      case txn :: txns => {
        txns.foldLeft( ActualAdjustment( txn, getBalance( txn.src ), taint ) )(
          { ( acc, e ) => { combine( acc, e ) } }
        )
      }
    }
  }
  def computeAdjustments( taintLabel : String, addr : String, taint : Float ) : Map[String,( Adjustment, Set[List[_ <: RHOCTxn]] )] = {
    computePaths( 
      new InitialAdjustment( taintLabel, addr, taint, taint )
    ).foldLeft( new HashMap[String,( Adjustment, Set[List[_ <: RHOCTxn]] )]() )(
      {
        ( acc, e ) => {
          val ( k, v ) = e
          val seed : Adjustment = new InitialAdjustment( taintLabel, addr, 0, taint )
          val adj : Adjustment =            
            v.foldLeft( seed )(
              { 
                ( acc1, path ) => {
                  combine(
                    acc1, 
                    computeAdjustment( taintLabel, addr, acc1.taintedBalance )( path.asInstanceOf[List[_ <: RHOCTxn]] )
                  )
                }
              }
            )

          val pair = ( adj, v.asInstanceOf[Set[List[RHOCTxn]]] )
          acc + ( k -> pair )
        }
      }
    )
  }

  def txnSetFromClosure( taintMap : Map[String,Set[_ <: RHOCTxn]] ) : Set[_ <: RHOCTxn] = {
    taintMap.values.fold( new HashSet[RHOCTxn]() )( ( acc, s ) => acc ++ s )
  }    

  // Backward calculation
  def computeTaint( taintMap : Map[String,Set[_ <: RHOCTxn]], taintAcc : Map[String,( Adjustment, Set[List[_ <: RHOCTxn]] )] )(
    txn : RHOCTxn, taintSrc : Float
  ) : Option[(Float, Set[List[_ <: RHOCTxn]])]  = {
    //println( s"computing taint for ${txn.trgt}" )
    taintAcc.get( txn.trgt ) match {
      case None => {
        val emptySet : Set[List[_ <: RHOCTxn]] = new HashSet[List[RHOCTxn]]()
        txn.justification.toList match {
          case Nil => {
            val tpp = ( taintSrc, new HashSet[List[RHOCTxn]]() )
            //println( s"Entry in taint map: $tpp" )
            Some( tpp )
          }
          case txns => {            
            val initProofs : Set[List[_ <: RHOCTxn]] =
              // Scala type checker madness!!!
              txn.justification.map( 
                ( t ) => t match { case rT : RHOCTxn => List( rT ) }
              )
            val seed : ( Float, Set[List[_ <: RHOCTxn]])  = 
              ( 0.asInstanceOf[Float], initProofs )
            val taintProofPair =
              txns.foldLeft( seed )(
                {
                  ( acc, tJ ) => {
                    val ( txnJ, amt, bal ) = tJ match {
                      case iTxn : InitialRHOCTxn => {
                        //println( s"case initial txn: ${iTxn}" )
                        ( iTxn, txn.amt, taintSrc )
                      }
                      case rTxn : RHOCTxnRep => {
                        //println( s"case intermediate txn: ${rTxn}" )
                        val txnBal = getBalance( rTxn )
                        val normBal = if ( txnBal == 0.0 ) { 1 } else { txnBal }
                      ( rTxn, txn.amt, normBal )
                      }
                    }
                    computeTaint( taintMap, taintAcc )( txnJ, taintSrc ) match {
                      case Some( ( taintJ, proof ) ) => {
                        val taint = ( amt * taintJ )/bal
                        //println( s"${txn} amt is ${txn.amt}" )
                        //println( s"${txn} bal is ${getBalance(txn)}" )
                        //println( s"${txnJ} amt is ${txnJ.amt}" )
                        //println( s"${txnJ} bal is ${bal}" )
                        //println( s"${txnJ} taint is ${taintJ}" )
                        //println( s"${txn} taint is ${taint}" )
                        val accProof : Set[List[_ <: RHOCTxn]] = acc._2
                        val proofExt : Set[List[_ <: RHOCTxn]] =
                          accProof.toList match {
                            case Nil => proof
                            case _ => {
                              accProof.flatMap( 
                                ( prefix ) => {
                                  proof.toList match {
                                    case Nil => { emptySet + prefix }
                                    case _ => proof.map( ( path ) => prefix ++ path )
                                  }
                                }
                              )
                            }
                          }
                        ( acc._1 + taint, proofExt )
                      }
                      case None => { ( 0, emptySet ) }
                    }
                  }
                }
              )
            //println( s"${txn.trgt}: taint proof pair: $taintProofPair" )
            Some( taintProofPair )
          }
        }        
      }
      case Some( ( adj, proofs ) ) => { Some( ( adj.taintedBalance, proofs ) ) }
    }
  }

  def computeTaints( taintMap : Map[String,Set[_ <: RHOCTxn]] )( 
    taintSrc : Float
  ) : Map[String,( Adjustment, Set[List[_ <: RHOCTxn]] )] = {
    val emptySet : Set[List[_ <: RHOCTxn]] = new HashSet[List[RHOCTxn]]()
    val txnSet : Set[_ <: RHOCTxn] = txnSetFromClosure( taintMap )

    txnSet.foldLeft( new HashMap[String,( Adjustment, Set[List[_ <: RHOCTxn]] )]() )(
      ( acc, txn ) => {
        acc.get( txn.trgt ) match {
          case None => {
            val pathContributions =
              txn.justification.toList.map(
                { ( txnJ ) => computeTaint( taintMap, acc )( txnJ.asInstanceOf[RHOCTxn], taintSrc ) }
              )
            val pathSumSeed : (Float,Set[List[_ <: RHOCTxn]]) = ( 0, new HashSet[List[_ <: RHOCTxn]] )
            val ( pathSum, proofs ) =
              pathContributions.foldLeft( pathSumSeed )(
                ( pathAcc, pathC ) => {
                  pathC match {
                    case Some( ( pathCTaint, pathCProof ) ) => {
                      ( pathAcc._1 + pathCTaint, pathAcc._2 ++ pathCProof )
                    }
                    case None => { ( 0, emptySet ) }
                  }
                }
              )

            val txnBal = getBalance( txn.trgt )
            val normBal = if ( txnBal == 0.0 ) { 1 } else { txnBal }

            val adj = new ActualAdjustment( txn, normBal, pathSum )

            acc + ( txn.src -> ( adj, proofs ) )
          }
          case Some( ( adj, proofs ) ) => acc
        }
      }
    )
  }

  def reportAdjustments(
    taintLabel : String, addr : String, taint : Float, 
    adjFileName : String, proofFileName : String, 
    dir : String
  ) : Unit = {
    val adjustmentsFile = new File( s"$dir/$adjFileName" )
    val proofFile = new File( s"$dir/$proofFileName" )
    val adjustmentsWriter = new BufferedWriter( new FileWriter( adjFileName ) )
    val proofWriter = new BufferedWriter( new FileWriter( proofFileName ) )
    val adjustments = computeAdjustments( taintLabel, addr, taint )
    for( ( k, v ) <- adjustments ) {
      adjustmentsWriter.write( s"$k, ${v._1.taintedBalance}\n" )
      proofWriter.write( s"$k, ${v._2}\n" )
    }
    adjustmentsWriter.flush()
    proofWriter.flush()
    adjustmentsWriter.close()
    proofWriter.close()
  }

  def reportAdjustments(
    taintLabel : String, addr : String, taint : Float
  ) : Unit = {
    reportAdjustments( taintLabel, addr, taint, adjustmentsFile, proofFile, reportingDir )
  }

  def reportTaints(
    taintLabel : String, addr : String, taint : Float,
    adjFileName : String, proofFileName : String, 
    dir : String
  ) : Unit = {
    val adjustmentsFile = new File( s"${dir}/${adjFileName}" )
    val proofFile = new File( s"${dir}/${proofFileName}" )
    val adjustmentsWriter = new BufferedWriter( new FileWriter( adjFileName ) )
    val proofWriter = new BufferedWriter( new FileWriter( proofFileName ) )
    val taintMap = close( new InitialRHOCTxn( taintLabel, addr, taint ) )
    val adjustments = computeTaints( taintMap )( taint )
    for( ( k, v ) <- adjustments ) {
      adjustmentsWriter.write( s"$k, ${v._1.taintedBalance}\n" )
      proofWriter.write( s"$k, ${v._2.map( _.map( _.hash ) )}\n" )
    }
    adjustmentsWriter.flush()
    proofWriter.flush()
    adjustmentsWriter.close()
    proofWriter.close()
  }

  def reportTaints(
    taintLabel : String, addr : String, taint : Float
  ) : Unit = {
    reportTaints( taintLabel, addr.toLowerCase, taint, adjustmentsFile, proofFile, reportingDir )
  }

}

case class Edge[Node](
  override val src           : Node,
  override val trgt          : Node,
  override val weight        : Float,
  override val hash          : String,
  override val blockId       : String,
  override val justification : Set[Edge[Node]]
) extends EdgeT[Node] with Justified[Edge[Node]]

trait AddressT {
  def addr      : String
  def balance   : Float
}

case class Address( 
  override val addr    : String, 
  override val balance : Float
) extends AddressT {
  override def equals( a : Any ) = {
    a match {
      case Address( `addr`, _ ) => true
      case _ => false
    }
  }
  override def hashCode = ( addr ).##
}

case class RHOCTxnEdge(
  override val src           : Address,
  override val trgt          : Address,
  override val weight        : Float,
  override val hash          : String,
  override val blockId       : String,
  override val justification : Set[RHOCTxnEdge]
) extends EdgeT[Address] with Justified[RHOCTxnEdge]

object RHOCTxnGraphClosure 
    extends JustifiedClosure[Address, RHOCTxnEdge] with InputCSVData {
  import AdjustmentConstants._

  // Use this initial edge to generate the Barcelona clique
  val barcelonaEdge : RHOCTxnEdge = {
    new RHOCTxnEdge(
      new Address( barcelonaLabel, 0 ),
      new Address( barcelonaAddr, barcelonaTaint ),
      barcelonaTaint,
      "scam",
      "scam",
      new HashSet[RHOCTxnEdge]()
    )
  }

  // Use this initial edge to generate the Pithia clique
  val pithiaEdge : RHOCTxnEdge = {
    new RHOCTxnEdge(
      new Address( pithiaLabel, 0 ),
      new Address( pithiaAddr, pithiaTaint ),
      pithiaTaint,
      "scam",
      "scam",
      new HashSet[RHOCTxnEdge]()
    )
  }

  def loadAndFormatWalletData( source : String, dir : String ) : Map[String,Float] = {
    loadWalletData( source, dir ).foldLeft( new HashMap[String,Float]() )(
      ( acc, walletArray ) => {
        acc + ( walletArray( 0 ).toLowerCase -> walletArray( 1 ).toFloat )
      }
    )
  }

  var balancesD : Option[Map[String,Float]] = None
  def balances() : Map[String,Float] = {
    balancesD match {
      case None => {
        val balanceMap = loadAndFormatWalletData( walletSource, sourceDir )
        balancesD = Some( balanceMap )
        balanceMap
      }
      case Some( balanceMap ) => balanceMap
    }
  }
    
  def loadAndFormatTxnData( source : String, dir : String ) : List[RHOCTxnEdge] = {
    for( txnArray <- loadTxnData( source, dir ) ) yield {
      RHOCTxnEdge(
        new Address( txnArray(4), 0 ),
        new Address( txnArray(5), 0 ),
        txnArray(6).toFloat,
        txnArray(0),
        txnArray(1),
        new HashSet[RHOCTxnEdge]()
      )
    }
  }

  var txnDataV : Option[List[RHOCTxnEdge]] = None
  def txnData() : List[RHOCTxnEdge] = {
    txnDataV match {
      case None => {
        val txnD = loadAndFormatTxnData( txnSource, sourceDir )
        txnDataV = Some( txnD )
        txnD
      }
      case Some( txnD ) => txnD
    }
  }

  // We run the transitive closure to generate the tainted
  // clique, i.e. the graph of transactions that communicate taint.
  // During the calculation of the transitive closure we calculate the
  // weight of each edge to be the fraction of the amt of the
  // transaction divided by the sum of all the outgoing transactions.

  def nextRHOCTxnWeightedEdges( txn : RHOCTxnEdge ) : Set[RHOCTxnEdge] = {
    val progeny : List[RHOCTxnEdge] = txnData().filter( ( txnD ) => { txnD.src == txn.trgt } )
    val seed : Float = 0
    val totalWeight = progeny.foldLeft( seed )( ( acc, t ) => { acc + t.weight } )
    val divisor = { 
      if ( totalWeight == 0 )
      { 1 }
      else { totalWeight }
    }

    progeny.map(
      ( t ) => {
        RHOCTxnEdge(
          t.src,
          t.trgt,
          ( t.weight / divisor ),
          t.hash,
          t.blockId,
          (new HashSet[RHOCTxnEdge]() + txn)
        )
      }
    ).toSet
  }    

  override def next = nextRHOCTxnWeightedEdges
  override def key = _.trgt

  def taintedClique( taintMap : Map[Address,Set[_ <: RHOCTxnEdge]] ) : List[RHOCTxnEdge] = {
    taintMap.values.fold( new HashSet[RHOCTxnEdge]() )(
      ( acc, s ) => {
        s match {
          case rTE : RHOCTxnEdge => acc ++ rTE
          case _ => throw new Exception( s"unexpected edge type: $s" )
        }
      }
    ).toList
  }

  def getClique( taintedEdge : RHOCTxnEdge ) : List[RHOCTxnEdge] = { taintedClique( close( taintedEdge ) ) }

  // Now, we can rerun the closure just on the clique and calculate
  // the taint at an address as the application of the weight to the
  // "balance" of the target address of the edge. Note that the balance here is
  // just the taint because we begin from the root of the clique where
  // the balance is entirely taint and carry that forward through the
  // calculation of the closure.

  def nextRHOCTxnTaint( clique : List[RHOCTxnEdge] )( txn : RHOCTxnEdge ) : Set[RHOCTxnEdge] = {
    val progeny : List[RHOCTxnEdge] = clique.filter( ( txnD ) => { txnD.src == txn.trgt } )
    progeny.map(
      ( t ) => {
        val trgtTaint : Float = txn.trgt.balance * t.weight
        RHOCTxnEdge(
          t.src,
          new Address( t.trgt.addr, trgtTaint ),
          trgtTaint,
          t.hash,
          t.blockId,
          (new HashSet[RHOCTxnEdge]() + txn)
        )
      }
    ).toSet
  }

  def adjustmentsMap( adjustments : List[RHOCTxnEdge] ) : Map[String,( Float, Float, Set[List[RHOCTxnEdge]] )] = {
    val empty : Set[List[RHOCTxnEdge]] = new HashSet[List[RHOCTxnEdge]]()
    val seed : Map[String,( Float, Float, Set[List[RHOCTxnEdge]] )] = new HashMap[String,( Float, Float, Set[List[RHOCTxnEdge]] )]()
    balances().foldLeft( seed )(
      ( acc, entry ) => {
        val ( addr, balance ) = entry
        val addrAdj : List[RHOCTxnEdge] = adjustments.filter( ( e ) => e.src.addr == addr )
        addrAdj match {
          case Nil => {
            val adj = ( balance, 0.toFloat, empty )
            acc + ( addr -> adj )
          }
          case edges : List[RHOCTxnEdge] => {
            val adjSeed : ( Float, Set[List[RHOCTxnEdge]] ) = ( 0, empty )
            val adj = edges.foldLeft( adjSeed )(
              ( adjAcc, e ) => {
                val ( adjAccW, adjAccPaths ) = adjAcc
                val rTE : RHOCTxnEdge = e.asInstanceOf[RHOCTxnEdge]
                val eProofs : Set[List[RHOCTxnEdge]] = rTE.paths().asInstanceOf[Set[List[RHOCTxnEdge]]]
                val eW : Float = rTE.weight
                ( adjAccW + eW, adjAccPaths ++ eProofs )
              }
            )
            val balAdj = ( balance, adj._1, adj._2 )
            acc + ( addr -> balAdj )
          }
        }
      }
    )
  }

  def reportAdjustments(
    adjustmentsMap : Map[String,( Float, Float, Set[List[RHOCTxnEdge]] )],
    adjFileName : String, proofFileName : String,
    dir : String
  ) : Unit = {
    val adjustmentsFile = new File( s"${dir}/${adjFileName}" )
    val proofFile = new File( s"${dir}/${proofFileName}" )
    val adjustmentsWriter = new BufferedWriter( new FileWriter( adjFileName ) )
    val proofWriter = new BufferedWriter( new FileWriter( proofFileName ) )
    for( ( k, v ) <- adjustmentsMap ) {
      val ( balance, adjustment, proof ) = v
      adjustmentsWriter.write( s"$k, ${balance}, ${adjustment}\n" )
      proofWriter.write( s"$k, ${proof}\n" )
    }
    adjustmentsWriter.flush()
    proofWriter.flush()
    adjustmentsWriter.close()
    proofWriter.close()
  }

  def reportAdjustmentsMap( adjustmentsMap : Map[String,( Float, Float, Set[List[RHOCTxnEdge]] )] ) : Unit = {
    reportAdjustments( adjustmentsMap, adjustmentsFile, proofFile, reportingDir )
  }

  val BarcelonaWeights = getClique( barcelonaEdge )

  object BarcelonaClique extends JustifiedClosure[Address, RHOCTxnEdge] {
    override def next = nextRHOCTxnTaint( BarcelonaWeights )
    override def key = _.trgt

    val BarcelonaAdjustments = getClique( barcelonaEdge )

    def reportAdjustments( ) : Unit = {
      reportAdjustmentsMap( adjustmentsMap( BarcelonaAdjustments ) )
    }
  }

  val PithiaWeights = getClique( pithiaEdge )

  object PithiaClique extends JustifiedClosure[Address, RHOCTxnEdge] {
    override def next = nextRHOCTxnTaint( PithiaWeights )
    override def key = _.trgt

    val PithiaAdjustments = getClique( pithiaEdge )

    def reportAdjustments( ) : Unit = {
      reportAdjustmentsMap( adjustmentsMap( PithiaAdjustments ) )
    }
  }
}
