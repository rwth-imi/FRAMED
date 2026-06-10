package com.framed.cdss;

import com.framed.core.Service;
import com.framed.core.spi.DeploymentValidator;

import java.util.*;
import java.util.logging.Logger;

/**
 * Acyclic Deterministic Reactor Network validator. Registered as a {@link DeploymentValidator}
 * so the orchestrator can run it after deployment without depending on this (domain) class.
 */
public class ADRN implements DeploymentValidator {
  private static final Logger logger = Logger.getLogger(ADRN.class.getName());

  private List<Reactor> actors = new ArrayList<>();
  private final List<Reactor> leafs = new ArrayList<>();
  private final List<Reactor> sources =  new ArrayList<>();
  private final Map<Reactor, List<Reactor>> adj = new HashMap<>();

  /** No-argument constructor used by {@link java.util.ServiceLoader}. */
  public ADRN() {
  }

  public ADRN(List<Reactor> actors) throws IllegalArgumentException {
    check(actors);
  }

  /**
   * Filters the deployed services down to {@link Reactor}s and validates that they form an
   * acyclic network. No-op when no reactors are present.
   */
  @Override
  public void validate(Collection<Service> services) {
    List<Reactor> reactors = new ArrayList<>();
    for (Service service : services) {
      if (service instanceof Reactor reactor) {
        reactors.add(reactor);
      }
    }
    if (!reactors.isEmpty()) {
      check(reactors);
    }
  }

  private void check(List<Reactor> actors) throws IllegalArgumentException {
    this.actors = actors;
    this.adj.clear();
    this.leafs.clear();
    this.sources.clear();
    for (Reactor actor : actors) {
      this.adj.putIfAbsent(actor, new ArrayList<>());
    }
    computeEdges();
    if (!isAcyclic()) {
      throw new IllegalArgumentException("Cyclic Graph.");
    } else {
      logger.info("Acyclic Deterministic Reactor Network instantiated.");
    }
  }



  private void computeEdges() {
    // from actor input and output channels, compute the edges of the network.
    Set<Reactor> actorsWithPredecessors = new HashSet<>();

    for (Reactor actor : actors) {
      processActorEdges(actor, actorsWithPredecessors);
    }

    identifySources(actorsWithPredecessors);
  }

  private void processActorEdges(Reactor actor1, Set<Reactor> actorsWithPredecessors) {
    boolean hasSuccessor = false;

    for (Reactor actor2 : actors) {
      if (actor1 == actor2) continue; // no reflexive edges in the network (acyclic condition).

      // if there is an output channel of actor1 that is an input channel of actor 2:
      // actor 2 is a successor of actor 1
      if (isSuccessor(actor1, actor2)) {
        this.adj.get(actor1).add(actor2);
        hasSuccessor = true;
        actorsWithPredecessors.add(actor2);
      }
    }

    if (!hasSuccessor) {
      // if no successor was found
      // actor1 is a leaf
      this.leafs.add(actor1);
    }
  }

  private boolean isSuccessor(Reactor actor1, Reactor actor2) {
    // for 2 actors, check whether actor2 is a successor of actor1
    Set<String> commons = new HashSet<>(actor1.getOutputChannels());
    commons.retainAll(actor2.getInputChannels());
    return !commons.isEmpty();
  }

  private void identifySources(Set<Reactor> actorsWithPredecessors) {
    // all actors without predecessors are sources.
    // input channels should not be empty, otherwise, those actors are just unreachable!!!
    for (Reactor actor : actors) {
      if (!actorsWithPredecessors.contains(actor) && !actor.getInputChannels().isEmpty()) {
        this.sources.add(actor);
      }
    }
  }


  private boolean isAcyclic(){
    // check wether (Actors, Edges) is an acyclic graph
    // Mark all the Actors as not visited and
    // not part of recursion stack
    List<Reactor> visited = new ArrayList<>();
    List<Reactor> recStack = new ArrayList<>();
    // Call the recursive helper function to
    // detect cycle in different DFS trees
    for (Reactor actor: this.actors) {
      if (isCyclicUtil(actor, visited, recStack)){
        return false;
      }
    }
    return true;
  }

  private boolean isCyclicUtil(Reactor actor, List<Reactor> visited, List<Reactor> recStack) {
    // Mark the current node as visited and
    // part of recursion stack
    if (recStack.contains(actor)) {
      return true;
    }

    if (visited.contains(actor)) {
      return false;
    }

    visited.add(actor);

    recStack.add(actor);

    List<Reactor> children = this.adj.get(actor);

    for (Reactor c : children)
      if (isCyclicUtil(c, visited, recStack))
        return true;

    recStack.remove(actor);

    return false;
  }
}
