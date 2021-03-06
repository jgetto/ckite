package ckite.states

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Future
import scala.util.Try
import org.slf4j.LoggerFactory
import java.util.Random
import ckite.Cluster
import ckite.rpc.WriteCommand
import java.util.concurrent.atomic.AtomicReference
import ckite.rpc.RequestVoteResponse
import ckite.util.Logging
import ckite.rpc.AppendEntriesResponse
import ckite.rpc.RequestVote
import ckite.rpc.AppendEntries
import ckite.RLog
import ckite.rpc.EnterJointConsensus
import java.util.concurrent.atomic.AtomicBoolean
import ckite.rpc.Command
import ckite.util.CKiteConversions._

/**
 *  •! RePCs from candidates and leaders.
 * •! Convert to candidate if election timeout elapses without
 * either:
 * •! Receiving valid AppendEntries RPC, or
 * •! Granting vote to candidate
 */
class Follower(cluster: Cluster) extends State with Logging {

  val electionTimeout = new ElectionTimeout(cluster)
  
  override def begin(term: Int) = electionTimeout restart

  override def stop = electionTimeout stop

  override def on[T](command: Command): T = cluster.forwardToLeader[T](command)

  override def on(appendEntries: AppendEntries): AppendEntriesResponse = {
    if (appendEntries.term < cluster.local.term) {
      AppendEntriesResponse(cluster.local.term, false)
    } else {
      electionTimeout.restart
      cluster.local.updateTermIfNeeded(appendEntries.term)
      
      if (cluster.updateLeader(appendEntries.leaderId)) LOG.info(s"Following ${cluster.leader}")

      val success = cluster.rlog.tryAppend(appendEntries)
      
      AppendEntriesResponse(cluster.local.term, success)
    }
  }

  override def on(requestVote: RequestVote): RequestVoteResponse = {
      cluster setNoLeader //Some member started an election. Assuming no Leader.
      
      cluster.local.updateTermIfNeeded(requestVote.term)
      val grantVote = checkGrantVotePolicy(requestVote)
      if (grantVote) {
        LOG.debug(s"Granting vote to ${requestVote.memberId} in term ${requestVote.term}")
        electionTimeout.restart
        cluster.local.votedFor.set(requestVote.memberId)
      } else {
        LOG.debug(s"Rejecting vote to ${requestVote.memberId} in term ${requestVote.term}")
      }
      RequestVoteResponse(cluster.local.term, grantVote)
  }
  
  private def checkGrantVotePolicy(requestVote: RequestVote) = {
    val votedFor = cluster.local.votedFor.get()
    (votedFor.isEmpty() || votedFor == requestVote.memberId) && isMuchUpToDate(requestVote)
  }

  private def isMuchUpToDate(requestVote: RequestVote) = {
    val lastLogEntry = cluster.rlog.getLastLogEntry
    lastLogEntry.isEmpty || (requestVote.lastLogTerm >= lastLogEntry.get.term && requestVote.lastLogIndex >= lastLogEntry.get.index)
  }

  private def isCurrentTerm(term: Int) = term == cluster.local.term
  
  override def toString = "Follower"
    
  override protected def getCluster: Cluster = cluster
    
}

class ElectionTimeout(cluster: Cluster) extends Logging {

  val scheduledFuture = new AtomicReference[ScheduledFuture[_]]()
  val random = new Random()

  def restart = {
    stop
    start
  }
  
  private def start = {
    val electionTimeout =  randomTimeout
    LOG.trace(s"New timeout is $electionTimeout ms")
    val task: Runnable = () => {
          cluster updateContextInfo
          
    	  LOG.debug("Timeout reached! Time to elect a new Leader")
    	  cluster.local becomeCandidate (cluster.local.term)
          
    }
    val future = cluster.scheduledElectionTimeoutExecutor.schedule(task, electionTimeout, TimeUnit.MILLISECONDS)
    val previousFuture = scheduledFuture.getAndSet(future)
    cancel(previousFuture)
  }

  private def randomTimeout = {
    val conf = cluster.configuration
    val diff = conf.maxElectionTimeout - conf.minElectionTimeout
    conf.minElectionTimeout + random.nextInt(if (diff > 0) diff.toInt else 1)
  }
  
  def stop() = {
    val future = scheduledFuture.get()
    cancel(future)
  }
  
  private def cancel(future: Future[_]) = if (future != null) future.cancel(false)
  
}