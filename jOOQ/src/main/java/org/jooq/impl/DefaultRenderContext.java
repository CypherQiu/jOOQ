/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Other licenses:
 * -----------------------------------------------------------------------------
 * Commercial licenses for this work are available. These replace the above
 * ASL 2.0 and offer limited warranties, support, maintenance, and commercial
 * database integrations.
 *
 * For more information, please visit: http://www.jooq.org/licenses
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
package org.jooq.impl;

import static java.lang.Boolean.TRUE;
// ...
import static org.jooq.SQLDialect.SQLITE;
import static org.jooq.conf.ParamType.INLINED;
import static org.jooq.conf.SettingsTools.renderLocale;
import static org.jooq.impl.Identifiers.QUOTES;
import static org.jooq.impl.Identifiers.QUOTE_END_DELIMITER;
import static org.jooq.impl.Identifiers.QUOTE_END_DELIMITER_ESCAPED;
import static org.jooq.impl.Identifiers.QUOTE_START_DELIMITER;
import static org.jooq.impl.Tools.BooleanDataKey.DATA_COUNT_BIND_VALUES;
import static org.jooq.impl.Tools.DataKey.DATA_APPEND_SQL;
import static org.jooq.impl.Tools.DataKey.DATA_PREPEND_SQL;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jooq.BindContext;
import org.jooq.Configuration;
import org.jooq.Constants;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Param;
import org.jooq.Query;
import org.jooq.QueryPart;
import org.jooq.QueryPartInternal;
import org.jooq.RenderContext;
import org.jooq.SQLDialect;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.conf.RenderFormatting;
import org.jooq.conf.RenderKeywordCase;
import org.jooq.conf.RenderNameCase;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.conf.SettingsTools;
import org.jooq.exception.ControlFlowSignal;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.ScopeMarker.ScopeContent;
import org.jooq.tools.JooqLogger;
import org.jooq.tools.StringUtils;

/**
 * @author Lukas Eder
 */
class DefaultRenderContext extends AbstractContext<RenderContext> implements RenderContext {

    private static final JooqLogger       log                = JooqLogger.getLogger(DefaultRenderContext.class);

    private static final Pattern          IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern          NEWLINE            = Pattern.compile("[\\n\\r]");
    private static final Set<String>      SQLITE_KEYWORDS;

    final StringBuilder                   sql;
    private final QueryPartList<Param<?>> bindValues;
    private int                           alias;
    private int                           indent;
    private Deque<Integer>                indentLock;
    private boolean                       separatorRequired;
    private boolean                       separator;
    private boolean                       newline;
    private Boolean                       isQuery;

    // [#1632] Cached values from Settings
    RenderKeywordCase                     cachedRenderKeywordCase;
    RenderNameCase                        cachedRenderNameCase;
    RenderQuotedNames                     cachedRenderQuotedNames;
    boolean                               cachedRenderFormatted;

    // [#6525] Cached values from Settings.renderFormatting
    String                                cachedIndentation;
    int                                   cachedIndentWidth;
    String                                cachedNewline;
    int                                   cachedPrintMargin;

    DefaultRenderContext(Configuration configuration) {
        super(configuration, null);

        Settings settings = configuration.settings();

        this.sql = new StringBuilder();
        this.bindValues = new QueryPartList<>();
        this.cachedRenderKeywordCase = SettingsTools.getRenderKeywordCase(settings);
        this.cachedRenderFormatted = Boolean.TRUE.equals(settings.isRenderFormatted());
        this.cachedRenderNameCase = SettingsTools.getRenderNameCase(settings);
        this.cachedRenderQuotedNames = SettingsTools.getRenderQuotedNames(settings);

        RenderFormatting formatting = settings.getRenderFormatting();
        if (formatting == null)
            formatting = new RenderFormatting();

        this.cachedNewline = formatting.getNewline() == null ? "\n" : formatting.getNewline();
        this.cachedIndentation = formatting.getIndentation() == null ? "  " : formatting.getIndentation();
        this.cachedIndentWidth = cachedIndentation.length();
        this.cachedPrintMargin = formatting.getPrintMargin() == null ? 80 : formatting.getPrintMargin();
    }

    DefaultRenderContext(RenderContext context) {
        this(context, true);
    }

    DefaultRenderContext(RenderContext context, boolean copyLocalState) {
        this(context.configuration());

        paramType(context.paramType());
        qualifyCatalog(context.qualifyCatalog());
        qualifySchema(context.qualifySchema());
        quote(context.quote());
        castMode(context.castMode());

        if (copyLocalState) {
            data().putAll(context.data());

            declareCTE = context.declareCTE();
            declareWindows = context.declareWindows();
            declareFields = context.declareFields();
            declareTables = context.declareTables();
            declareAliases = context.declareAliases();
        }
    }

    // ------------------------------------------------------------------------
    // BindContext API
    // ------------------------------------------------------------------------

    @Override
    public final BindContext bindValue(Object value, Field<?> field) throws DataAccessException {
        throw new UnsupportedOperationException();
    }

    final QueryPartList<Param<?>> bindValues() {
        return bindValues;
    }

    // ------------------------------------------------------------------------
    // RenderContext API
    // ------------------------------------------------------------------------

    @Override
    void scopeMarkStart0(QueryPart part) {
        applyNewLine();
        ScopeStackElement e = scopeStack.getOrCreate(part);
        e.positions = new int[] { sql.length(), -1 };
        e.bindIndex = peekIndex();
        e.indent = indent;
        resetSeparatorFlags();
    }

    @Override
    void scopeMarkEnd0(QueryPart part) {
        applyNewLine();
        ScopeStackElement e = scopeStack.getOrCreate(part);
        e.positions[1] = sql.length();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public QueryPart scopeMapping(QueryPart part) {
        if (scopeStack.inScope() && part instanceof ScopeMappable) {
            ScopeStackElement e = scopeStack.get(part);

            if (e != null && e.mapped != null) {
                QueryPart result = e.mapped;

                // [#13148] Prevent undesired unwrapping of scope mappables by re-wrapping them.
                if (part instanceof ScopeMappableWrapper)
                    result = ((ScopeMappableWrapper) part).wrap(result);

                return result;
            }
        }

        return part;
    }

    @Override
    public RenderContext scopeRegister(QueryPart part, boolean forceNew, QueryPart mapped) {
        if (scopeStack.inScope()) {
            ScopeStackElement e;

            if (part instanceof TableImpl) {
                Table<?> root = (Table<?>) part;
                Table<?> child = root;
                List<Table<?>> tables = new ArrayList<>();

                while (root instanceof TableImpl && (child = ((TableImpl<?>) root).child) != null) {
                    tables.add(root);
                    root = child;
                }

                e = forceNew
                    ? scopeStack.create(root)
                    : scopeStack.getOrCreate(root);

                if (e.joinNode == null)
                    e.joinNode = new JoinNode(configuration(), root);

                JoinNode childNode = e.joinNode;
                for (int i = tables.size() - 1; i >= 0; i--) {
                    Table<?> t = tables.get(i);
                    ForeignKey<?, ?> k = ((TableImpl<?>) t).childPath;

                    JoinNode next = childNode.children.get(k);
                    if (next == null) {
                        next = new JoinNode(configuration(), t);
                        childNode.children.put(k, next);
                    }

                    childNode = next;
                }
            }
            else if (forceNew)
                e = scopeStack.create(part);
            else
                e = scopeStack.getOrCreate(part);

            e.mapped = mapped;
        }

        return this;
    }

    @Override
    void scopeStart0() {
        for (ScopeStackElement e : scopeStack)
            if (e.part != e.mapped && !(e.part instanceof ScopeNestable))
                scopeStack.set(e.part, null);
    }

    @Override
    void scopeEnd0() {
        ScopeMarker[] markers = ScopeMarker.values();
        ScopeStackElement[] beforeFirst = new ScopeStackElement[markers.length];
        ScopeStackElement[] afterLast = new ScopeStackElement[markers.length];
        ScopeContent[] content = new ScopeContent[markers.length];

        for (ScopeMarker marker : markers) {
            if (!marker.topLevelOnly || subqueryLevel() == 0) {
                int i = marker.ordinal();
                ScopeContent o = content[i] = (ScopeContent) data(marker.key);

                if (o != null && !o.isEmpty()) {
                    beforeFirst[i] = scopeStack.get(marker.beforeFirst);
                    afterLast[i] = scopeStack.get(marker.afterLast);
                }
            }
        }

        outer:
        for (ScopeStackElement e1 : scopeStack.iterable(e -> e.scopeLevel == scopeStack.scopeLevel())) {
            String replacedSQL = null;
            QueryPartList<Param<?>> insertedBindValues = null;

            if (e1.positions == null) {
                continue outer;
            }

            // [#11367] TODO: Move this logic into a ScopeMarker as well
            //          TODO: subqueryLevel() is lower than scopeLevel if we use implicit join in procedural logic
            else if (e1.joinNode != null && !e1.joinNode.children.isEmpty()) {
                replacedSQL = configuration
                    .dsl()
                    .renderContext()
                    .declareTables(true)
                    .sql('(')
                    .formatIndentStart(e1.indent)
                    .formatIndentStart()
                    .formatNewLine()
                    .visit(e1.joinNode.joinTree())
                    .formatNewLine()
                    .sql(')')
                    .render();
            }
            else {

                elementLoop:
                for (int i = 0; i < beforeFirst.length; i++) {
                    ScopeStackElement e = beforeFirst[i];
                    ScopeContent c = content[i];

                    if (e1 == e && c != null) {
                        DefaultRenderContext ctx = new DefaultRenderContext(this, false);
                        markers[i].renderer.render(
                            (DefaultRenderContext) ctx.formatIndentStart(e.indent),
                            e,
                            afterLast[i],
                            c
                        );

                        replacedSQL = ctx.render();
                        insertedBindValues = ctx.bindValues();
                        break elementLoop;
                    }
                }
            }

            if (replacedSQL != null) {
                sql.replace(e1.positions[0], e1.positions[1], replacedSQL);
                int shift = replacedSQL.length() - (e1.positions[1] - e1.positions[0]);

                inner:
                for (ScopeStackElement e2 : scopeStack) {
                    if (e2.positions == null)
                        continue inner;

                    if (e2.positions[0] > e1.positions[0]) {
                        e2.positions[0] = e2.positions[0] + shift;
                        e2.positions[1] = e2.positions[1] + shift;
                    }
                }

                if (insertedBindValues != null) {
                    bindValues.addAll(e1.bindIndex - 1, insertedBindValues);

                    inner:
                    for (ScopeStackElement e2 : scopeStack) {
                        if (e2.positions == null)
                            continue inner;

                        if (e2.bindIndex > e1.bindIndex)
                            e2.bindIndex = e2.bindIndex + insertedBindValues.size();
                    }
                }
            }
        }
    }

    @Override
    public final String peekAlias() {
        return "alias_" + (alias + 1);
    }

    @Override
    public final String nextAlias() {
        return "alias_" + (++alias);
    }

    @Override
    public final String render() {
        String prepend = null;
        String append = null;

        if (TRUE.equals(isQuery)) {
            prepend = (String) data(DATA_PREPEND_SQL);
            append = (String) data(DATA_APPEND_SQL);
        }

        String result = sql.toString();

        return prepend == null && append == null
             ? result
             : format()
             ? (prepend != null ? prepend + (prepend.endsWith(cachedNewline) ? "" : cachedNewline) : "")
                 + result
                 + (append != null ? ";" + (append.endsWith(cachedNewline) ? "" : cachedNewline) + append : "")
             : (prepend != null ? prepend + (prepend.endsWith(" ") ? "" : " ") : "")
                 + result
                 + (append != null ? ";" + (append.endsWith(" ") ? "" : " ") + append : "");
    }

    @Override
    public final String render(QueryPart part) {
        return new DefaultRenderContext(this).visit(part).render();
    }

    @Override
    public final RenderContext keyword(String keyword) {
        return visit(DSL.keyword(keyword));
    }

    @Override
    public final RenderContext sql(String s) {
        return sql(s, s == null || !cachedRenderFormatted);
    }

    @Override
    public final RenderContext sql(String s, boolean literal) {
        if (!literal)
            s = Tools.replaceAll(s, NEWLINE.matcher(s), r -> r.group() + indentation());

        if (stringLiteral())
            s = StringUtils.replace(s, "'", stringLiteralEscapedApos);

        applyNewLine();
        sql.append(s);
        resetSeparatorFlags();
        return this;
    }

    @Override
    public final RenderContext sqlIndentStart(String c) {
        return sql(c).sqlIndentStart();
    }

    @Override
    public final RenderContext sqlIndentEnd(String c) {
        return sqlIndentEnd().sql(c);
    }

    @Override
    public final RenderContext sqlIndentStart() {
        return formatIndentStart().formatNewLine();
    }

    @Override
    public final RenderContext sqlIndentEnd() {
        return formatIndentEnd().formatNewLine();
    }

    @Override
    public final RenderContext sql(char c) {
        applyNewLine();

        if (c == '\'' && stringLiteral())
            sql.append(stringLiteralEscapedApos);
        else
            sql.append(c);

        resetSeparatorFlags();
        return this;
    }

    @Override
    public final RenderContext sqlIndentStart(char c) {
        return sql(c).sqlIndentStart();
    }

    @Override
    public final RenderContext sqlIndentEnd(char c) {
        return sqlIndentEnd().sql(c);
    }

    @Override
    public final RenderContext sql(int i) {
        applyNewLine();
        sql.append(i);
        resetSeparatorFlags();
        return this;
    }

    @Override
    public final RenderContext sql(long l) {
        applyNewLine();
        sql.append(l);
        resetSeparatorFlags();
        return this;
    }

    @Override
    public final RenderContext sql(float f) {
        applyNewLine();
        sql.append(floatFormat().format(f));
        resetSeparatorFlags();
        return this;
    }

    @Override
    public final RenderContext sql(double d) {
        applyNewLine();
        sql.append(doubleFormat().format(d));
        resetSeparatorFlags();
        return this;
    }

    private final void resetSeparatorFlags() {
        separatorRequired = false;
        separator = false;
        newline = false;
    }

    @Override
    public final RenderContext formatNewLine() {
        if (cachedRenderFormatted)
            newline = true;

        return this;
    }

    private final void applyNewLine() {
        if (newline) {
            sql.append(cachedNewline);
            sql.append(indentation());
        }
    }

    @Override
    public final RenderContext formatNewLineAfterPrintMargin() {
        if (cachedRenderFormatted && cachedPrintMargin > 0)
            if (sql.length() - sql.lastIndexOf(cachedNewline) > cachedPrintMargin)
                formatNewLine();

        return this;
    }

    private final String indentation() {
        return StringUtils.leftPad("", indent, cachedIndentation);
    }

    @Override
    public final RenderContext format(boolean format) {
        cachedRenderFormatted = format;
        return this;
    }

    @Override
    public final boolean format() {
        return cachedRenderFormatted;
    }

    @Override
    public final RenderContext formatSeparator() {
        if (!separator && !newline) {
            if (cachedRenderFormatted)
                formatNewLine();
            else
                sql(" ", true);

            separator = true;
        }

        return this;
    }

    @Override
    public final RenderContext separatorRequired(boolean separatorRequired) {
        this.separatorRequired = separatorRequired;
        return this;
    }

    @Override
    public final boolean separatorRequired() {
        return separatorRequired && !separator && !newline;
    }

    @Override
    public final RenderContext formatIndentStart() {
        return formatIndentStart(cachedIndentWidth);
    }

    @Override
    public final RenderContext formatIndentEnd() {
        return formatIndentEnd(cachedIndentWidth);
    }

    @Override
    public final RenderContext formatIndentStart(int i) {
        if (cachedRenderFormatted)
            indent += i;

//            // [#9193] If we've already generated the separator (and indentation)
//            if (newline)
//                sql.append(cachedIndentation);

        return this;
    }

    @Override
    public final RenderContext formatIndentEnd(int i) {
        if (cachedRenderFormatted)
            indent -= i;

        return this;
    }

    private final Deque<Integer> indentLock() {
        if (indentLock == null)
            indentLock = new ArrayDeque<>();

        return indentLock;
    }

    @Override
    public final RenderContext formatIndentLockStart() {
        if (cachedRenderFormatted) {
            indentLock().push(indent);
            String[] lines = NEWLINE.split(sql);
            indent = lines[lines.length - 1].length();
        }

        return this;
    }

    @Override
    public final RenderContext formatIndentLockEnd() {
        if (cachedRenderFormatted)
            indent = indentLock().pop();

        return this;
    }

    @Override
    public final RenderContext formatPrintMargin(int margin) {
        cachedPrintMargin = margin;
        return this;
    }

    @Override
    public final RenderContext literal(String literal) {
        // Literal usually originates from NamedQueryPart.getName(). This could
        // be null for CustomTable et al.
        if (literal == null)
            return this;

        SQLDialect family = family();

        // Quoting is needed when explicitly requested...
        boolean needsQuote =

            // [#2367] ... but in SQLite, quoting "normal" literals is generally
            // asking for trouble, as SQLite bends the rules here, see
            // http://www.sqlite.org/lang_keywords.html for details ...
            (family != SQLITE && quote())

        ||

            // [#2367] ... yet, do quote when an identifier is a SQLite keyword
            (family == SQLITE && SQLITE_KEYWORDS.contains(literal.toUpperCase(renderLocale(configuration().settings()))))

        ||

            // [#1982] [#3360] ... yet, do quote when an identifier contains special characters
            (family == SQLITE && !IDENTIFIER_PATTERN.matcher(literal).matches());

        literal = applyNameCase(literal);







        if (needsQuote) {
            char[][][] quotes = QUOTES.get(family);

            char start = quotes[QUOTE_START_DELIMITER][0][0];
            char end = quotes[QUOTE_END_DELIMITER][0][0];

            sql(start);

            // [#4922] This micro optimisation does seem to have a significant
            //         effect as the replace call can be avoided in almost all
            //         situations
            if (literal.indexOf(end) > -1)
                sql(StringUtils.replace(literal, new String(quotes[QUOTE_END_DELIMITER][0]), new String(quotes[QUOTE_END_DELIMITER_ESCAPED][0])), true);
            else
                sql(literal, true);

            sql(end);
        }
        else
            sql(literal, true);

        return this;
    }

    @Override
    final String applyNameCase(String literal) {
        if (RenderNameCase.LOWER == cachedRenderNameCase ||
            RenderNameCase.LOWER_IF_UNQUOTED == cachedRenderNameCase && !quote())
            return literal.toLowerCase(renderLocale(configuration().settings()));
        else if (RenderNameCase.UPPER == cachedRenderNameCase ||
                 RenderNameCase.UPPER_IF_UNQUOTED == cachedRenderNameCase && !quote())
            return literal.toUpperCase(renderLocale(configuration().settings()));
        else
            return literal;
    }

    @Override
    protected final void visit0(QueryPartInternal internal) {
        if (isQuery == null) {
            isQuery = internal instanceof Query;

            if (TRUE.equals(settings().isTransformPatterns()) && configuration().requireCommercial(() -> "SQL transformations are a commercial only feature. Please consider upgrading to the jOOQ Professional Edition or jOOQ Enterprise Edition.")) {



            }
        }











        int before = bindValues.size();
        internal.accept(this);
        int after = bindValues.size();

        // [#4650] In PostgreSQL, UDTConstants are always inlined as ROW(?, ?)
        //         as the PostgreSQL JDBC driver doesn't support SQLData. This
        //         means that the above internal.accept(this) call has already
        //         collected the bind variable. The same is true if custom data
        //         type bindings use Context.visit(Param), in case of which we
        //         must not collect the current Param
        if (after == before && paramType != INLINED && internal instanceof Param) {
            Param<?> param = (Param<?>) internal;

            if (!param.isInline()) {
                bindValues.add(param);

                Integer threshold = settings().getInlineThreshold();
                if (threshold != null && threshold > 0) {
                    checkForceInline(threshold);
                }
                else {
                    switch (family()) {
























                        // [#5701] Tests were conducted with PostgreSQL 9.5 and pgjdbc 9.4.1209
                        case POSTGRES:
                        case YUGABYTEDB:
                            checkForceInline(32767);
                            break;

                        case SQLITE:
                            checkForceInline(999);
                            break;

                        default:
                            break;
                    }
                }
            }
        }
    }

    private final void checkForceInline(int max) throws ForceInlineSignal {
        if (bindValues.size() > max)
            if (TRUE.equals(data(DATA_COUNT_BIND_VALUES)))
                throw new ForceInlineSignal();
    }

    // ------------------------------------------------------------------------
    // Object API
    // ------------------------------------------------------------------------

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("rendering    [").append(render()).append("]\n");
        sb.append("formatting   [").append(format()).append("]\n");
        sb.append("parameters   [").append(paramType).append("]\n");

        toString(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------------
    // Static initialisation
    // ------------------------------------------------------------------------

    static {
        SQLITE_KEYWORDS = new HashSet<>();

        // [#2367] Taken from http://www.sqlite.org/lang_keywords.html
        SQLITE_KEYWORDS.addAll(Arrays.asList(
            "ABORT",
            "ACTION",
            "ADD",
            "AFTER",
            "ALL",
            "ALTER",
            "ANALYZE",
            "AND",
            "AS",
            "ASC",
            "ATTACH",
            "AUTOINCREMENT",
            "BEFORE",
            "BEGIN",
            "BETWEEN",
            "BY",
            "CASCADE",
            "CASE",
            "CAST",
            "CHECK",
            "COLLATE",
            "COLUMN",
            "COMMIT",
            "CONFLICT",
            "CONSTRAINT",
            "CREATE",
            "CROSS",
            "CURRENT",
            "CURRENT_DATE",
            "CURRENT_TIME",
            "CURRENT_TIMESTAMP",
            "DATABASE",
            "DEFAULT",
            "DEFERRABLE",
            "DEFERRED",
            "DELETE",
            "DESC",
            "DETACH",
            "DISTINCT",
            "DO",
            "DROP",
            "EACH",
            "ELSE",
            "END",
            "ESCAPE",
            "EXCEPT",
            "EXCLUDE",
            "EXCLUSIVE",
            "EXISTS",
            "EXPLAIN",
            "FAIL",
            "FILTER",
            "FOLLOWING",
            "FOR",
            "FOREIGN",
            "FROM",
            "FULL",
            "GLOB",
            "GROUP",
            "GROUPS",
            "HAVING",
            "IF",
            "IGNORE",
            "IMMEDIATE",
            "IN",
            "INDEX",
            "INDEXED",
            "INITIALLY",
            "INNER",
            "INSERT",
            "INSTEAD",
            "INTERSECT",
            "INTO",
            "IS",
            "ISNULL",
            "JOIN",
            "KEY",
            "LEFT",
            "LIKE",
            "LIMIT",
            "MATCH",
            "NATURAL",
            "NO",
            "NOT",
            "NOTHING",
            "NOTNULL",
            "NULL",
            "OF",
            "OFFSET",
            "ON",
            "OR",
            "ORDER",
            "OTHERS",
            "OUTER",
            "OVER",
            "PARTITION",
            "PLAN",
            "PRAGMA",
            "PRECEDING",
            "PRIMARY",
            "QUERY",
            "RAISE",
            "RANGE",
            "RECURSIVE",
            "REFERENCES",
            "REGEXP",
            "REINDEX",
            "RELEASE",
            "RENAME",
            "REPLACE",
            "RESTRICT",
            "RIGHT",
            "ROLLBACK",
            "ROW",
            "ROWS",
            "SAVEPOINT",
            "SELECT",
            "SET",
            "TABLE",
            "TEMP",
            "TEMPORARY",
            "THEN",
            "TIES",
            "TO",
            "TRANSACTION",
            "TRIGGER",
            "UNBOUNDED",
            "UNION",
            "UNIQUE",
            "UPDATE",
            "USING",
            "VACUUM",
            "VALUES",
            "VIEW",
            "VIRTUAL",
            "WHEN",
            "WHERE",
            "WINDOW",
            "WITH",
            "WITHOUT"
        ));



        /*
         * So, you've found the piece of logic that displays our beautifully-crafted ASCII-art logo that
         * we display in the log files to our jOOQ Open Source Edition and jOOQ Free Trial Edition users
         *
         * You probably came here to see if you can somehow turn it off, e.g. because you found this
         * page here:
         *
         *   http://stackoverflow.com/q/28272284/521799
         *
         * And yes! You can:
         *
         *   a) turn off your logger for org.jooq.Constants
         *   b) set the -Dorg.jooq.no-logo=true property
         *   c) simply patch this file in your sources and rebuild jOOQ
         *   d) buy a commercial license
         *
         * Hint: While a) - c) work, d) is the right answer :-)
         *
         * But before you do any of a) - c), consider this. We give away this awesome software for free,
         * and we'd love to continue giving it away for free, so all we would like to ask you is to
         * continue to show your love and our brand to everyone involved in your software simply in the
         * log files when you load jOOQ. Please don't remove our logo.
         *
         * Thank you very much! If you absolutely must remove this logo, and can live with the guilt
         * and shame, below is the system property that deactivates it.
         *
         * Cheers from the jOOQ Team.
         *
         * --------------------------------------------------
         * DEAR USER, PLEASE READ THE ABOVE BEFORE PROCEEDING
         */
        if (!Boolean.getBoolean("org.jooq.no-logo")) {
            JooqLogger l = JooqLogger.getLogger(Constants.class);
            String message;

            message = "Thank you for using jOOQ " + Constants.FULL_VERSION;






            l.info("\n                                      " +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@  @@        @@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@        @@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@  @@  @@    @@@@@@@@@@" +
                   "\n@@@@@@@@@@  @@@@  @@  @@    @@@@@@@@@@" +
                   "\n@@@@@@@@@@        @@        @@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@" +
                   "\n@@@@@@@@@@        @@        @@@@@@@@@@" +
                   "\n@@@@@@@@@@    @@  @@  @@@@  @@@@@@@@@@" +
                   "\n@@@@@@@@@@    @@  @@  @@@@  @@@@@@@@@@" +
                   "\n@@@@@@@@@@        @@  @  @  @@@@@@@@@@" +
                   "\n@@@@@@@@@@        @@        @@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@  @@@@@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@" +
                   "\n@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@  " + message +
                   "\n                                      ");
        }





        if (!Boolean.getBoolean("org.jooq.no-tips")) {
            JooqLogger l = JooqLogger.getLogger(Constants.class);

            l.info("\n\njOOQ tip of the day: " + Tips.randomTip() + "\n");
        }

    }

    /**
     * A query execution interception signal.
     * <p>
     * This exception is used as a signal for jOOQ's internals to abort query
     * execution, and return generated SQL back to batch execution.
     */
    class ForceInlineSignal extends ControlFlowSignal {

        public ForceInlineSignal() {
            if (log.isDebugEnabled())
                log.debug("Re-render query", "Forcing bind variable inlining as " + configuration().dialect() + " does not support " + peekIndex() + " bind variables (or more) in a single query");
        }
    }

    static class Rendered {
        String                  sql;
        QueryPartList<Param<?>> bindValues;
        int                     skipUpdateCounts;

        Rendered(String sql, QueryPartList<Param<?>> bindValues, int skipUpdateCounts) {
            this.sql = sql;
            this.bindValues = bindValues;
            this.skipUpdateCounts = skipUpdateCounts;
        }

        @Override
        public String toString() {
            return sql;
        }
    }
}
