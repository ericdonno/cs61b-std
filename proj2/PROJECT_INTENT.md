# DungeonMind Project Intent

> Status: canonical product-intent brief
>
> This document records the builder's current intent. Earlier TDD, build-guide, review, and brainstorm documents remain useful design history, but when their product direction conflicts with this document, this document takes precedence.

## 1. Why this project exists

DungeonMind began as CS61B Project 2 (BYOG), but its primary purpose is now to help the builder learn modern Agent engineering by building a real game AI system.

The project should demonstrate more than an LLM API call. It should implement an observable Agent loop in which enemies perceive a game world, reason under limited information, form intentions, use tools, act through the game engine, receive feedback, communicate, and replan.

Technical sophistication is not sufficient by itself. The Agent system must ultimately create a dungeon game that is more interesting, immersive, and strategically expressive for the player.

## 2. Product vision

The player is an adventurer descending through a procedural dungeon. Each floor contains a small number of enemies or guards with different capabilities and tactical competence.

Enemies are not omniscient obstacles. They exist inside the same world as the player:

- They learn about events through their own sight, hearing, damage, and messages.
- They hold incomplete beliefs and may make reasonable mistakes.
- They can patrol, investigate, search, fight, retreat, warn allies, raise alarms, and operate dungeon facilities through physical actions.
- They may form squads and coordinate, but coordination emerges through communication between independent enemies.
- The player can observe their behavior, infer their tactical intent, deceive them, bypass them, isolate them, or defeat them physically.

The desired experience is a contest of information and tactics, not a contest against an invisible omniscient controller.

## 3. Agent identity and boundaries

Each enemy is an independent Agent with its own:

- perception;
- belief state;
- working memory;
- goals and current intent;
- decision process;
- action and communication history.

There is no shared hive mind and no single LLM consciousness controlling a group of enemy bodies.

Squad behavior and alert propagation must occur through in-world mechanisms such as shouting, signals, alarms, direct contact, or other explicit communication actions. If an enemy is killed, isolated, interrupted, or unable to communicate, information it did not transmit does not automatically become known to its allies.

An enemy must not automatically know:

- the player's current position when the player was neither perceived nor reported;
- the player's unobserved health, inventory, abilities, or intentions;
- distant allies' observations or state without communication;
- information held by a dead enemy that was never transmitted;
- the authoritative global game state merely because the engine contains it.

Enemies may infer unknown facts, but an inference remains uncertain and can be manipulated by the player.

## 4. Floor gameplay loop

The current intended floor loop is:

1. The player enters a relatively safe part of an unknown floor with limited visibility.
2. The player explores rooms and routes, locates the stairs, and observes enemy types, patrols, senses, and communication behavior.
3. The player chooses how to create progress: avoidance, deception, ambush, selective combat, or direct assault are all valid in principle.
4. Detection, loud actions, discovered bodies, or a successful alarm can escalate local enemies into investigation, search, or defense states. Information spreads only through valid in-world communication.
5. The stairs remain usable but are difficult to approach safely. Descending requires a short interaction or safe window, so the player must bypass, distract, disable, isolate, or defeat the defense.
6. Killing every enemy is not required. The player descends with the consequences of resource and health expenditure; enemies and their working memories end with the floor.

## 5. Memory scope

The first version does not need a cross-floor player profile or long-term adaptation to the player's habits.

It does require per-enemy, per-floor working memory, including facts such as:

- last seen position and time;
- heard sound and estimated source;
- who reported a piece of information;
- confidence and age of uncertain information;
- discovered bodies or changed facilities;
- locations already searched;
- current plan, execution result, and reason for replanning.

Long-term memory may be reconsidered later only if gameplay and worldbuilding provide a believable reason for information to persist between floors.

## 6. Agent MVP: definition of done

The Agent MVP must use real Agent engineering. It is not complete merely because the game can call an LLM.

It should include:

1. **Agent runtime** — a stateful graph runtime such as LangGraph, or an equivalent explicit workflow with cycles, conditional transitions, and feedback.
2. **Independent Agent state** — each enemy has private perception, beliefs, working memory, intent, message history, and execution feedback. Enemies may share a model service without sharing consciousness or context.
3. **Tool calling** — Agents use constrained tools to observe authoritative slices of game state, query tactical possibilities, communicate, interact with facilities, and submit structured intentions.
4. **Structured output and validation** — model output is schema-constrained; the engine verifies knowledge provenance, permissions, reachability, and legality.
5. **Short-term memory and checkpointing** — per-floor observations, messages, and planning state survive across Agent turns.
6. **Multi-Agent message passing** — information exchange is explicit, attributable, time-sensitive, and limited by game rules.
7. **Planning and execution feedback** — a high-level intention is translated by deterministic planners and skills; success, blockage, lost targets, and other results return to the Agent and can trigger replanning.
8. **Asynchronous, event-driven operation** — LLM latency must not block the game loop. Meaningful game events trigger deliberation.
9. **Tracing and evaluation** — developers can inspect what each Agent perceived, which tools it called, why it chose an intention, how long it took, whether execution succeeded, and whether fallback occurred.
10. **A visible coordinated encounter** — at least one repeatable scene demonstrates independent enemies sharing information and producing a player-visible tactical response.

## 7. Role of traditional rule AI

Traditional AI is a supporting system, not the project's final claim.

The existing `RuleBasedBrain` and future deterministic logic serve as:

- a baseline for controlled comparison with Agent AI;
- a fast reflex and execution layer for movement, collision, combat, navigation, and urgent reactions;
- a fallback when the Agent runtime or model is unavailable.

The intended hierarchy remains:

```text
Enemy perception and belief
        -> Agent reasoning and tool use
        -> structured StrategicIntent
        -> deterministic planner / skills
        -> ActionQueue and atomic actions
        -> execution feedback to the Agent
```

## 8. Technologies not required merely for their label

The project should use Agent technologies when they teach or enable a real capability, not as a checklist of fashionable terms.

The following are not required for the first Agent MVP unless later evidence creates a need:

- a vector database for a small amount of per-floor working memory;
- cross-floor player profiling;
- reinforcement learning or model fine-tuning;
- autonomous prompt or code rewriting;
- unrestricted free-form conversations among enemy Agents;
- MCP solely to wrap tools that are already internal to the game and Agent runtime.

These are deferred, not forbidden. They can be added when they solve a demonstrated problem or become an explicit learning objective.

## 9. Open product questions

The project will not decide the final balance among combat, stealth, deception, and avoidance before a playable prototype exists.

That balance must be informed by playing the game. The prototype should support multiple approaches in principle, then observation and playtesting should determine which approaches deserve stronger rewards and deeper systems.

Worldbuilding, enemy factions, specialized abilities, D&D-inspired lighting, sound propagation, traps, skills, healing, and longer-term memory remain possible extensions. They should be introduced in service of the core Agent experience rather than becoming independent feature checklists.

## 10. Current north star

DungeonMind succeeds when a player can truthfully say:

> I was not fighting an omniscient script. I was dealing with enemies that perceived incomplete evidence, formed intentions, communicated, coordinated, made understandable mistakes, and could be outthought.

The builder succeeds when the implementation makes that behavior traceable as a genuine Agent loop rather than disguising a one-shot LLM call behind game terminology.
