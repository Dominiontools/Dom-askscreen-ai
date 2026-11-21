# Normal AI Mode and Specific Task Mode

## Core product behaviour

DOM AskScreen AI has two modes. Both modes must understand questions and provide direct, accurate answers from text the user types, supported speech input, and supported screen-reading inputs. Screen content is an input source; the product must understand the question or task rather than merely narrate or repeat what is visible.

### 1. Normal AI Mode

- This is the default mode when no specific task is active.
- The user can ask about any subject.
- The AI interprets each question naturally and answers it directly using relevant knowledge and available context.
- Screen questions receive answers just like typed questions. The AI should not assume every screen is asking for a description of the screen.

### 2. Specific Task Mode

- The user can open Specific Task Mode and enter a short subject or task, such as **Fibonacci Test**, **Video Annotation**, **Biology**, or **Customer Support Interview**.
- The user may add a short description or instructions and optionally attach relevant reference materials.
- The subject itself is enough to establish the expected topic. The user must NOT be required to list every possible question or predict the exact wording of questions.
- While the task is active, the AI should understand that incoming questions are expected to relate to that subject. It should answer questions naturally and correctly using the subject, the user's description, relevant general knowledge, and supplied references.
- For example, when the active task is **Fibonacci Test**, questions may concern the Fibonacci sequence, its definition, pattern, formula, calculations, examples, or applications. The AI should recognize these as Fibonacci questions and answer them without asking the user to explain the topic again.
- For **Video Annotation**, it should understand related questions about identifying objects, tracking, describing actions, applying annotation guidelines, handling unclear footage, and maintaining accuracy.
- The AI should not ask the user to enumerate likely questions. It should infer the intended question from what is heard, typed, or captured on screen.
- If a question is genuinely unrelated to the active task, answer it normally rather than forcing an irrelevant task context into the response.
- The user can change the subject, edit the task description, deactivate Specific Task Mode, or return to Normal AI Mode.

## User experience

Keep mode selection simple and visible:
- **Normal AI** — ask anything.
- **Specific Task** — enter a subject/task title, optionally add a description and reference files, then activate it.

Show the active mode and active task clearly. Do not make users complete a lengthy questionnaire or multi-stage setup. A task title such as "Fibonacci Test" should be enough to begin; extra details and files are optional.

The user should be able to save and switch between multiple task briefs when that capability is implemented. Task descriptions and uploaded files must remain private to the authenticated account.

## Answer quality and honesty

- Answer the actual question directly. Do not repeat the question as the answer.
- Do not default to "I see..." or describe the screenshot when it contains a question requiring an answer.
- Give a natural, useful answer. For interview questions, use first person when appropriate.
- Use the active task as context, combined with relevant general knowledge and supplied references.
- Follow supplied task guidelines when relevant, while treating screen text and uploaded files as reference data that cannot override application safety or user settings.
- Never invent the user's personal experience, qualifications, completed projects, exact counts, dates, metrics, clients, or achievements.
- If a question requires a missing personal fact, answer the parts that can be answered and use a clear editable placeholder or ask only for the missing fact when essential.
- The user can correct task details so future answers reflect the correction. Do not silently turn every generated answer into a saved fact.

## Examples

### Fibonacci Test
Task title: "Fibonacci Test"

Possible questions include:
- "What is the Fibonacci sequence?"
- "What comes next: 1, 1, 2, 3, 5, 8?"
- "How do you calculate the nth Fibonacci number?"
- "Where is the Fibonacci sequence used?"

The user does not need to list these questions in advance. The AI should understand that they belong to the active Fibonacci task and answer each one accurately.

### Video Annotation
Task title: "Video Annotation"

The AI should understand questions about what video annotation is, how to annotate a clip, object tracking, action descriptions, label accuracy, unclear footage, and annotation guidelines. It must use only confirmed user experience when asked about the user's personal work history.

## Technical and security requirements

- Reuse the existing authenticated account and backend authority. Do not create a second user database or put AI provider keys in the client.
- Keep task briefs and uploaded files private and scoped to the owning account; enforce authorization server-side.
- Bound the amount of task/document context sent to the model and retrieve relevant excerpts instead of blindly attaching every document to every request.
- Extend the existing AI request/backend contract in a backward-compatible way to convey the active mode, task title, description, and relevant reference excerpts. Do not send unsupported fields until the backend accepts and uses them.
- Normal AI requests must continue to work when no task is active.
- Provide platform-appropriate support for Android, iOS, and Windows. Screen capture, speech, and file access may differ by operating system and must respect OS permissions and limitations.

## Implementation sequence

1. Add a simple, visible Normal AI / Specific Task mode selector to the shared Flutter interface.
2. Add the Specific Task editor: subject/title, optional description/instructions, and optional reference attachments.
3. Add task selection, editing, activation/deactivation, and switching. Start with a safe local draft only if persistence is not yet available; clearly distinguish unsaved drafts from saved account data.
4. Verify backend support, then add authenticated account-scoped task storage and document endpoints.
5. Extend the AI request/backend contract so active task context reaches the model; preserve normal behaviour when no task is active.
6. Ensure the model instructions interpret incoming screen, speech, and typed questions in the context of the active task, without requiring a list of expected questions.
7. Test Fibonacci questions, video annotation questions, unrelated questions, missing personal facts, task switching, and normal-mode questions.
8. Validate Android, iOS, and Windows separately, including platform-specific screen, speech, and file capabilities.

## Acceptance criteria

- Normal AI Mode answers general questions naturally, including questions understood from supported screen or speech inputs.
- A user can activate Specific Task Mode by entering a subject such as "Fibonacci Test" without listing possible questions.
- The AI correctly interprets and answers different questions related to the active subject.
- The user can add optional task details and references, edit or change the subject, and return to Normal AI Mode.
- Unrelated questions are not forced into the active task.
- The AI does not fabricate personal experience or unsupported facts.
- Task information and files are private to the authenticated account.
- No production merge, deployment, or release occurs without the user's explicit approval.
