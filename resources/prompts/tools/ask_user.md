Ask the user a question to gather information, clarify ambiguity, understand preferences, or offer choices.

Use this tool when you need to interact with the user during execution:
1. Gather user preferences or requirements
2. Clarify ambiguous instructions
3. Get decisions on implementation choices as you work
4. Offer choices about what direction to take

When NOT to use:
- For trivial decisions you can make yourself
- When the answer is already clear from context
- To confirm every small step (avoid over-asking)

Usage notes:
- Keep questions concise, specific, and ending with a question mark
- Provide 2-4 options when applicable, each with a brief description
- If you recommend a specific option, make it the first in the list and add "(Recommended)" to its label
- Ask one question at a time
- Set `allowFreeform` to false when only the provided options are valid choices
- When `allowFreeform` is true (the default), the user can type a custom answer, so do not include an "Other" option
