# Working on file-drop

Every file follows the rulebook: https://vbarbarosh.github.io/rules/
(source: https://github.com/vbarbarosh/rules). Read it before writing code;
it changes, so read it again in a new session.

- JavaScript: [FORMATTING.md](https://github.com/vbarbarosh/rules/blob/master/FORMATTING.md)
  and [the rule index](https://github.com/vbarbarosh/rules/blob/master/docs/rules.md)
- Bash: every script follows [bin/templ](https://github.com/vbarbarosh/rules/blob/master/bin/templ)
- Logs: `[group_uid][sender] details`, see [drafts/logs.md](https://github.com/vbarbarosh/rules/blob/master/drafts/logs.md);
  the helpers are in `src/helpers/`
- Commits: `scope: description`, lowercase, 72 characters at most, see
  [drafts/commits.md](https://github.com/vbarbarosh/rules/blob/master/drafts/commits.md)
- Java (`src/android/`): the same braces as JavaScript, a method's `{` on
  the next line, `else` and `catch` on a new line, braces always; snake_case
  names, as in receipt-drop

Check JS and CSS before handing work over; it must print nothing:

    npx vbarbarosh/rules src

The linter does not check everything: names, parentheses around compound
operands (FMT-27), log senders, Java and the project layout are checked by
hand.
