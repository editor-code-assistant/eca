# Workflows

There are multiple ways to use ECA while developing, here are some tips and suggested approches.

## Give initial context (`/init`)

It's recommended to run at least once `/init` per project to create a `AGENTS.md` file with the standards of the projects like lint, tests, this gives a good base context to LLM in all next prompts.

## Complex tasks solving (`plan`)

For solving complex tasks wher you wanna have control over how LLM will solve it, ECA's `plan` agent is perfect for that:

1. Start a chat and [switch to __plan__ agent](./features.md#agents)
2. Send a prompt with the task you wanna plan to do, e.g: "Let's fix bug X", "Find the problem X"

Doing that LLM will receive a detailed guideline to solve that divided into 3 stages:
- __Explore__: Run tools and analyze the code, search web, and get all the needed info
- __Decide__: Consider alternatives and ways to solve your task, then decide which one is the best
- __Present__: It will present the plan and what files gonna be changed, asking if you wanna see a preview of the changed files.

3. From there you can either:
   - Iterate on the plan if it's not what you desire, sending prompts to change the plan, LLM will update accordingly.
   - Confirm to preview the changes sending something like "yes".
   - __Most likely__: Switch to `code` agent and ask to execute like "do it".
   
This is a really good workflow to use the best of LLM.

## Quick refactors - (`rewrite`)

Sometimes you don't want to start a whole chat and iterate to add a small portion of code or do a quick code change, so [ECA's rewrite feature](./features.md#rewrite) may fit well.

1. Select the part of the code you wanna LLM to change/replace like whole functions/methods.
2. Call ECA rewrite in your editor
3. Fill the prompt with what you want like "Change X to Y", "Implement this function".
4. after rewrite is done, decide if you wanna accept, retry a new one or reject it.

