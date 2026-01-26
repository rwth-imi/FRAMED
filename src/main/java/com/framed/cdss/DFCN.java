package com.framed.cdss;

import com.framed.orchestrator.Main;

import java.util.*;
import java.util.logging.Logger;

public class DFCN {
  private static final Logger logger = Logger.getLogger(Main.class.getName());

  private final List<Actor> actors;
  private final List<Actor> leafs = new ArrayList<>();
  private final List<Actor> sources =  new ArrayList<>();
  private final Map<Actor, List<Actor>> adj = new HashMap<>();


  public DFCN(List<Actor> actors) throws IllegalArgumentException {

    this.actors = actors;
    for (Actor actor : actors) {
      this.adj.putIfAbsent(actor, new ArrayList<>());
    }
    computeEdges();
    if (!isAcyclic()) {
      throw new IllegalArgumentException("Cyclic Graph.");
    } else {
      logger.info("Acyclic Data Flow Process Network instantiated.");
    }
  }



  private void computeEdges() {
    // from actor input and output channels, compute the edges of the network.
    Set<Actor> actorsWithPredecessors = new HashSet<>();

    for (Actor actor : actors) {
      processActorEdges(actor, actorsWithPredecessors);
    }

    identifySources(actorsWithPredecessors);
  }

  private void processActorEdges(Actor actor1, Set<Actor> actorsWithPredecessors) {
    boolean hasSuccessor = false;

    for (Actor actor2 : actors) {
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

  private boolean isSuccessor(Actor actor1, Actor actor2) {
    // for 2 actors, check whether actor2 is a successor of actor1
    Set<String> commons = new HashSet<>(actor1.getOutputChannels());
    commons.retainAll(actor2.getInputChannels());
    return !commons.isEmpty();
  }

  private void identifySources(Set<Actor> actorsWithPredecessors) {
    // all actors without predecessors are sources.
    // input channels should not be empty, otherwise, those actors are just unreachable!!!
    for (Actor actor : actors) {
      if (!actorsWithPredecessors.contains(actor) && !actor.getInputChannels().isEmpty()) {
        this.sources.add(actor);
      }
    }
  }


  private boolean isAcyclic(){
    // check wether (Actors, Edges) is an acyclic graph
    // Mark all the Actors as not visited and
    // not part of recursion stack
    List<Actor> visited = new ArrayList<>();
    List<Actor> recStack = new ArrayList<>();
    // Call the recursive helper function to
    // detect cycle in different DFS trees
    for (Actor actor: this.actors) {
      if (isCyclicUtil(actor, visited, recStack)){
        return false;
      }
    }
    return true;
  }

  private boolean isCyclicUtil(Actor actor, List<Actor> visited, List<Actor> recStack) {
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

    List<Actor> children = this.adj.get(actor);

    for (Actor c : children)
      if (isCyclicUtil(c, visited, recStack))
        return true;

    recStack.remove(actor);

    return false;
  }
}
