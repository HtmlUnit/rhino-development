/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript.regexp;

import org.mozilla.javascript.AbstractEcmaObjectOperations;
import org.mozilla.javascript.Callable;
import org.mozilla.javascript.Constructable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.IdFunctionObject;
import org.mozilla.javascript.IdScriptableObject;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.ScriptRuntimeES6;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.SymbolKey;
import org.mozilla.javascript.TopLevel;
import org.mozilla.javascript.Undefined;

/**
 * This class implements the RegExp native object.
 *
 * <p>Revision History: Implementation in C by Brendan Eich Initial port to Java by Norris Boyd from
 * jsregexp.c version 1.36 Merged up to version 1.38, which included Unicode support. Merged bug
 * fixes in version 1.39. Merged JSFUN13_BRANCH changes up to 1.32.2.13
 *
 * @author Brendan Eich
 * @author Norris Boyd
 */
public class NativeRegExp extends IdScriptableObject {
    private static final long serialVersionUID = 4965263491464903264L;

    private static final Object REGEXP_TAG = new Object();

    // type of match to perform
    public static final int TEST = 0;
    public static final int MATCH = 1;
    public static final int PREFIX = 2;

    public static void init(Context cx, Scriptable scope, boolean sealed) {

        NativeRegExp proto = NativeRegExpInstantiator.withLanguageVersion(cx.getLanguageVersion());
        proto.re = RegExpEngine.compileRE(cx, "", null, false);
        proto.activatePrototypeMap(MAX_PROTOTYPE_ID);
        proto.setParentScope(scope);
        proto.setPrototype(getObjectPrototype(scope));

        NativeRegExpCtor ctor = new NativeRegExpCtor();
        // Bug #324006: ECMA-262 15.10.6.1 says "The initial value of
        // RegExp.prototype.constructor is the builtin RegExp constructor."
        proto.defineProperty("constructor", ctor, ScriptableObject.DONTENUM);

        ScriptRuntime.setFunctionProtoAndParent(ctor, cx, scope);

        ctor.setImmunePrototypeProperty(proto);

        if (sealed) {
            proto.sealObject();
            ctor.sealObject();
        }

        defineProperty(scope, "RegExp", ctor, ScriptableObject.DONTENUM);

        ScriptRuntimeES6.addSymbolSpecies(cx, scope, ctor);
    }

    NativeRegExp(Scriptable scope, RECompiled regexpCompiled) {
        this.re = regexpCompiled;
        setLastIndex(ScriptRuntime.zeroObj);
        ScriptRuntime.setBuiltinProtoAndParent(this, scope, TopLevel.Builtins.RegExp);
    }

    @Override
    public String getClassName() {
        return "RegExp";
    }

    /**
     * Gets the value to be returned by the typeof operator called on this object.
     *
     * @see org.mozilla.javascript.ScriptableObject#getTypeOf()
     * @return "object"
     */
    @Override
    public String getTypeOf() {
        return "object";
    }

    Scriptable compile(Context cx, Scriptable scope, Object[] args) {
        if (args.length >= 1
                && args[0] instanceof NativeRegExp
                && (args.length == 1 || args[1] == Undefined.instance)) {
            // Avoid recompiling the regex
            this.re = ((NativeRegExp) args[0]).re;
        } else {
            String pattern;
            if (args.length == 0 || args[0] == Undefined.instance) {
                pattern = "";
            } else if (args[0] instanceof NativeRegExp) {
                pattern = new String(((NativeRegExp) args[0]).re.source);
            } else {
                pattern = escapeRegExp(args[0]);
            }

            String flags =
                    args.length > 1 && args[1] != Undefined.instance
                            ? ScriptRuntime.toString(args[1])
                            : null;

            // Passing a regex and flags is allowed in ES6, but forbidden in ES5 and lower.
            // Spec ref: 15.10.4.1 in ES5, 22.2.4.1 in ES6
            if (args.length > 0
                    && args[0] instanceof NativeRegExp
                    && flags != null
                    && cx.getLanguageVersion() < Context.VERSION_ES6) {
                throw ScriptRuntime.typeErrorById("msg.bad.regexp.compile");
            }

            this.re = RegExpEngine.compileRE(cx, pattern, flags, false);
        }
        setLastIndex(ScriptRuntime.zeroObj);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append('/');
        if (re.source.length != 0) {
            buf.append(re.source);
        } else {
            // See bugzilla 226045
            buf.append("(?:)");
        }
        buf.append('/');
        appendFlags(buf);
        return buf.toString();
    }

    private void appendFlags(StringBuilder buf) {
        if ((re.flags & RegExpEngine.JSREG_GLOB) != 0) buf.append('g');
        if ((re.flags & RegExpEngine.JSREG_FOLD) != 0) buf.append('i');
        if ((re.flags & RegExpEngine.JSREG_MULTILINE) != 0) buf.append('m');
        if ((re.flags & RegExpEngine. JSREG_DOTALL) != 0) buf.append('s');
        if ((re.flags & RegExpEngine.JSREG_STICKY) != 0) buf.append('y');
    }

    NativeRegExp() {}

    private static RegExpImpl getImpl(Context cx) {
        return (RegExpImpl) ScriptRuntime.getRegExpProxy(cx);
    }

    private static String escapeRegExp(Object src) {
        String s = ScriptRuntime.toString(src);
        // Escape any naked slashes in regexp source, see bug #510265
        StringBuilder sb = null; // instantiated only if necessary
        int start = 0;
        int slash = s.indexOf('/');
        while (slash > -1) {
            if (slash == start || s.charAt(slash - 1) != '\\') {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(s, start, slash);
                sb.append("\\/");
                start = slash + 1;
            }
            slash = s.indexOf('/', slash + 1);
        }
        if (sb != null) {
            sb.append(s, start, s.length());
            s = sb.toString();
        }
        return s;
    }

    Object execSub(Context cx, Scriptable scopeObj, Object[] args, int matchType) {
        RegExpImpl reImpl = getImpl(cx);
        String str;
        if (args.length == 0) {
            str = reImpl.input;
            if (str == null) {
                str = ScriptRuntime.toString(Undefined.instance);
            }
        } else {
            str = ScriptRuntime.toString(args[0]);
        }

        boolean globalOrSticky = (re.flags & RegExpEngine.JSREG_GLOB) != 0 || (re.flags & RegExpEngine.JSREG_STICKY) != 0;
        double d = 0;
        if (globalOrSticky) {
            d = ScriptRuntime.toInteger(lastIndex);

            if (d < 0 || str.length() < d) {
                setLastIndex(ScriptRuntime.zeroObj);
                return null;
            }
        }

        int[] indexp = {(int) d};
        Object rval = executeRegExp(cx, scopeObj, reImpl, str, indexp, matchType);
        if (globalOrSticky) {
            if (rval == null || rval == Undefined.instance) {
                setLastIndex(ScriptRuntime.zeroObj);
            } else {
                setLastIndex(Double.valueOf(indexp[0]));
            }
        }
        return rval;
    }

    /*
     * indexp is assumed to be an array of length 1
     */
    Object executeRegExp(
            Context cx, Scriptable scope, RegExpImpl res, String str, int[] indexp, int matchType) {
        REGlobalData gData = new REGlobalData();

        int start = indexp[0];
        int end = str.length();
        if (start > end) start = end;
        //
        // Call the recursive matcher to do the real work.
        //
        boolean matches = RegExpEngine.matchRegExp(cx, gData, re, str, start, end, res.multiline);
        if (!matches) {
            if (matchType != PREFIX) return null;
            return Undefined.instance;
        }
        int index = gData.cp;
        int ep = indexp[0] = index;
        int matchlen = ep - (start + gData.skipped);
        index -= matchlen;
        Object result;
        Scriptable obj;

        if (matchType == TEST) {
            /*
             * Testing for a match and updating cx.regExpImpl: don't allocate
             * an array object, do return true.
             */
            result = Boolean.TRUE;
            obj = null;
        } else {
            /*
             * The array returned on match has element 0 bound to the matched
             * string, elements 1 through re.parenCount bound to the paren
             * matches, an index property telling the length of the left context,
             * and an input property referring to the input string.
             */
            result = cx.newArray(scope, 0);
            obj = (Scriptable) result;

            String matchstr = str.substring(index, index + matchlen);
            obj.put(0, obj, matchstr);
        }

        if (re.parenCount == 0) {
            res.parens = null;
            res.lastParen = new SubString();
        } else {
            SubString parsub = null;
            int num;
            res.parens = new SubString[re.parenCount];
            for (num = 0; num < re.parenCount; num++) {
                int cap_index = gData.parensIndex(num);
                if (cap_index != -1) {
                    int cap_length = gData.parensLength(num);
                    parsub = new SubString(str, cap_index, cap_length);
                    res.parens[num] = parsub;
                    if (matchType != TEST) obj.put(num + 1, obj, parsub.toString());
                } else {
                    if (matchType != TEST) obj.put(num + 1, obj, Undefined.instance);
                }
            }
            res.lastParen = parsub;
        }

        if (!(matchType == TEST)) {
            /*
             * Define the index and input properties last for better for/in loop
             * order (so they come after the elements).
             */
            obj.put("index", obj, Integer.valueOf(start + gData.skipped));
            obj.put("input", obj, str);
        }

        if (res.lastMatch == null) {
            res.lastMatch = new SubString();
            res.leftContext = new SubString();
            res.rightContext = new SubString();
        }
        res.lastMatch.str = str;
        res.lastMatch.index = index;
        res.lastMatch.length = matchlen;

        res.leftContext.str = str;
        if (cx.getLanguageVersion() == Context.VERSION_1_2) {
            /*
             * JS1.2 emulated Perl4.0.1.8 (patch level 36) for global regexps used
             * in scalar contexts, and unintentionally for the string.match "list"
             * psuedo-context.  On "hi there bye", the following would result:
             *
             * Language     while(/ /g){print("$`");}   s/ /$`/g
             * perl4.036    "hi", "there"               "hihitherehi therebye"
             * perl5        "hi", "hi there"            "hihitherehi therebye"
             * js1.2        "hi", "there"               "hihitheretherebye"
             *
             * Insofar as JS1.2 always defined $` as "left context from the last
             * match" for global regexps, it was more consistent than perl4.
             */
            res.leftContext.index = start;
            res.leftContext.length = gData.skipped;
        } else {
            /*
             * For JS1.3 and ECMAv2, emulate Perl5 exactly:
             *
             * js1.3        "hi", "hi there"            "hihitherehi therebye"
             */
            res.leftContext.index = 0;
            res.leftContext.length = start + gData.skipped;
        }

        res.rightContext.str = str;
        res.rightContext.index = ep;
        res.rightContext.length = end - ep;

        return result;
    }

    int getFlags() {
        return re.flags;
    }

    private static final int Id_lastIndex = 1,
            Id_source = 2,
            Id_flags = 3,
            Id_global = 4,
            Id_ignoreCase = 5,
            Id_multiline = 6,
            Id_dotAll = 7,
            Id_sticky = 8,
            MAX_INSTANCE_ID = 8;

    @Override
    protected int getMaxInstanceId() {
        return MAX_INSTANCE_ID;
    }

    @Override
    protected int findInstanceIdInfo(String s) {
        int id;
        switch (s) {
            case "lastIndex":
                id = Id_lastIndex;
                break;
            case "source":
                id = Id_source;
                break;
            case "flags":
                id = Id_flags;
                break;
            case "global":
                id = Id_global;
                break;
            case "ignoreCase":
                id = Id_ignoreCase;
                break;
            case "multiline":
                id = Id_multiline;
                break;
            case "dotAll":
                id = Id_dotAll;
                break;
            case "sticky":
                id = Id_sticky;
                break;
            default:
                id = 0;
                break;
        }

        if (id == 0) return super.findInstanceIdInfo(s);

        int attr;
        switch (id) {
            case Id_lastIndex:
                attr = lastIndexAttr;
                break;
            case Id_source:
            case Id_flags:
            case Id_global:
            case Id_ignoreCase:
            case Id_multiline:
            case Id_dotAll:
            case Id_sticky:
                attr = PERMANENT | READONLY | DONTENUM;
                break;
            default:
                throw new IllegalStateException();
        }
        return instanceIdInfo(attr, id);
    }

    @Override
    protected String getInstanceIdName(int id) {
        switch (id) {
            case Id_lastIndex:
                return "lastIndex";
            case Id_source:
                return "source";
            case Id_flags:
                return "flags";
            case Id_global:
                return "global";
            case Id_ignoreCase:
                return "ignoreCase";
            case Id_multiline:
                return "multiline";
            case Id_dotAll:
                return "dotAll";
            case Id_sticky:
                return "sticky";
        }
        return super.getInstanceIdName(id);
    }

    @Override
    protected Object getInstanceIdValue(int id) {
        switch (id) {
            case Id_lastIndex:
                return lastIndex;
            case Id_source:
                return new String(re.source);
            case Id_flags:
                {
                    StringBuilder buf = new StringBuilder();
                    appendFlags(buf);
                    return buf.toString();
                }
            case Id_global:
                return ScriptRuntime.wrapBoolean((re.flags & RegExpEngine.JSREG_GLOB) != 0);
            case Id_ignoreCase:
                return ScriptRuntime.wrapBoolean((re.flags & RegExpEngine.JSREG_FOLD) != 0);
            case Id_multiline:
                return ScriptRuntime.wrapBoolean((re.flags & RegExpEngine.JSREG_MULTILINE) != 0);
            case Id_dotAll:
                return ScriptRuntime.wrapBoolean((re.flags & RegExpEngine.JSREG_DOTALL) != 0);
            case Id_sticky:
                return ScriptRuntime.wrapBoolean((re.flags & RegExpEngine.JSREG_STICKY) != 0);
        }
        return super.getInstanceIdValue(id);
    }

    private void setLastIndex(ScriptableObject thisObj, Object value) {
        if ((thisObj.getAttributes("lastIndex") & READONLY) != 0) {
            throw ScriptRuntime.typeErrorById("msg.modify.readonly", "lastIndex");
        }
        ScriptableObject.putProperty(thisObj, "lastIndex", value);
    }

    private void setLastIndex(Object value) {
        if ((lastIndexAttr & READONLY) != 0) {
            throw ScriptRuntime.typeErrorById("msg.modify.readonly", "lastIndex");
        }
        lastIndex = value;
    }

    @Override
    protected void setInstanceIdValue(int id, Object value) {
        switch (id) {
            case Id_lastIndex:
                setLastIndex(value);
                return;
            case Id_source:
            case Id_flags:
            case Id_global:
            case Id_ignoreCase:
            case Id_multiline:
            case Id_dotAll:
            case Id_sticky:
                return;
        }
        super.setInstanceIdValue(id, value);
    }

    @Override
    protected void setInstanceIdAttributes(int id, int attr) {
        if (id == Id_lastIndex) {
            lastIndexAttr = attr;
            return;
        }
        super.setInstanceIdAttributes(id, attr);
    }

    @Override
    protected void initPrototypeId(int id) {
        if (id == SymbolId_match) {
            initPrototypeMethod(REGEXP_TAG, id, SymbolKey.MATCH, "[Symbol.match]", 1);
            return;
        }
        if (id == SymbolId_matchAll) {
            initPrototypeMethod(REGEXP_TAG, id, SymbolKey.MATCH_ALL, "[Symbol.matchAll]", 1);
            return;
        }
        if (id == SymbolId_search) {
            initPrototypeMethod(REGEXP_TAG, id, SymbolKey.SEARCH, "[Symbol.search]", 1);
            return;
        }

        String s;
        int arity;
        switch (id) {
            case Id_compile:
                arity = 2;
                s = "compile";
                break;
            case Id_toString:
                arity = 0;
                s = "toString";
                break;
            case Id_toSource:
                arity = 0;
                s = "toSource";
                break;
            case Id_exec:
                arity = 1;
                s = "exec";
                break;
            case Id_test:
                arity = 1;
                s = "test";
                break;
            case Id_prefix:
                arity = 1;
                s = "prefix";
                break;
            default:
                throw new IllegalArgumentException(String.valueOf(id));
        }
        initPrototypeMethod(REGEXP_TAG, id, s, arity);
    }

    @Override
    public Object execIdCall(
            IdFunctionObject f, Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (!f.hasTag(REGEXP_TAG)) {
            return super.execIdCall(f, cx, scope, thisObj, args);
        }
        int id = f.methodId();
        switch (id) {
            case Id_compile:
                return realThis(thisObj, f).compile(cx, scope, args);

            case Id_toString:
                // thisObj != scope is a strange hack but i had no better idea for the moment
                if (thisObj != scope && thisObj instanceof NativeObject) {
                    Object sourceObj = thisObj.get("source", thisObj);
                    String source =
                            sourceObj.equals(NOT_FOUND) ? "undefined" : escapeRegExp(sourceObj);
                    Object flagsObj = thisObj.get("flags", thisObj);
                    String flags = flagsObj.equals(NOT_FOUND) ? "undefined" : flagsObj.toString();

                    return "/" + source + "/" + flags;
                }
                return realThis(thisObj, f).toString();

            case Id_toSource:
                return realThis(thisObj, f).toString();

            case Id_exec:
                return js_exec(cx, scope, thisObj, args);

            case Id_test:
                {
                    Object x = realThis(thisObj, f).execSub(cx, scope, args, TEST);
                    return Boolean.TRUE.equals(x) ? Boolean.TRUE : Boolean.FALSE;
                }

            case Id_prefix:
                return realThis(thisObj, f).execSub(cx, scope, args, PREFIX);

            case SymbolId_match:
                return js_SymbolMatch(cx, scope, thisObj, args);

            case SymbolId_matchAll:
                return js_SymbolMatchAll(cx, scope, thisObj, args);

            case SymbolId_search:
                Scriptable scriptable =
                        (Scriptable) realThis(thisObj, f).execSub(cx, scope, args, MATCH);
                return scriptable == null ? -1 : scriptable.get("index", scriptable);
        }
        throw new IllegalArgumentException(String.valueOf(id));
    }

    public static Object regExpExec(
            Scriptable regexp, String string, Context cx, Scriptable scope) {
        // See ECMAScript spec 22.2.7.1
        Object execMethod = ScriptRuntime.getObjectProp(regexp, "exec", cx, scope);
        if (execMethod instanceof Callable) {
            return ((Callable) execMethod).call(cx, scope, regexp, new Object[] {string});
        }
        return NativeRegExp.js_exec(cx, scope, regexp, new Object[] {string});
    }

    private Object js_SymbolMatch(
            Context cx, Scriptable scope, Scriptable thisScriptable, Object[] args) {
        // See ECMAScript spec 22.2.6.8
        var thisObj = ScriptableObject.ensureScriptableObject(thisScriptable);

        String string = ScriptRuntime.toString(args.length > 0 ? args[0] : Undefined.instance);
        String flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(thisObj, "flags", cx));
        boolean fullUnicode = flags.indexOf('u') != -1 || flags.indexOf('v') != -1;

        if (flags.indexOf('g') == -1) return regExpExec(thisObj, string, cx, scope);

        setLastIndex(thisObj, ScriptRuntime.zeroObj);
        Scriptable result = cx.newArray(scope, 0);
        int i = 0;
        while (true) {
            Object match = regExpExec(thisObj, string, cx, scope);
            if (match == null) {
                if (i == 0) return null;
                else return result;
            }

            String matchStr =
                    ScriptRuntime.toString(ScriptRuntime.getObjectIndex(match, 0, cx, scope));
            result.put(i++, result, matchStr);

            if (matchStr.isEmpty()) {
                long thisIndex =
                        ScriptRuntime.toLength(
                                ScriptRuntime.getObjectProp(thisObj, "lastIndex", cx));
                long nextIndex = ScriptRuntime.advanceStringIndex(string, thisIndex, fullUnicode);
                setLastIndex(thisObj, nextIndex);
            }
        }
    }

    static Object js_exec(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        return realThis(thisObj, "exec").execSub(cx, scope, args, MATCH);
    }

    private Object js_SymbolMatchAll(
            Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        // See ECMAScript spec 22.2.6.9
        if (!ScriptRuntime.isObject(thisObj)) {
            throw ScriptRuntime.typeErrorById("msg.arg.not.object", ScriptRuntime.typeof(thisObj));
        }

        String s = ScriptRuntime.toString(args.length > 0 ? args[0] : Undefined.instance);

        Scriptable topLevelScope = ScriptableObject.getTopLevelScope(scope);
        Function defaultConstructor =
                ScriptRuntime.getExistingCtor(cx, topLevelScope, getClassName());
        Constructable c =
                AbstractEcmaObjectOperations.speciesConstructor(cx, thisObj, defaultConstructor);

        String flags = ScriptRuntime.toString(ScriptRuntime.getObjectProp(thisObj, "flags", cx));

        Scriptable matcher = c.construct(cx, scope, new Object[] {thisObj, flags});

        long lastIndex =
                ScriptRuntime.toLength(ScriptRuntime.getObjectProp(thisObj, "lastIndex", cx));
        ScriptRuntime.setObjectProp(matcher, "lastIndex", lastIndex, cx);
        boolean global = flags.indexOf('g') != -1;
        boolean fullUnicode = flags.indexOf('u') != -1 || flags.indexOf('v') != -1;

        return new NativeRegExpStringIterator(scope, matcher, s, global, fullUnicode);
    }

    private static NativeRegExp realThis(Scriptable thisObj, IdFunctionObject f) {
        return realThis(thisObj, f.getFunctionName());
    }

    private static NativeRegExp realThis(Scriptable thisObj, String functionName) {
        return ensureType(thisObj, NativeRegExp.class, functionName);
    }

    @Override
    protected int findPrototypeId(Symbol k) {
        if (SymbolKey.MATCH.equals(k)) {
            return SymbolId_match;
        }
        if (SymbolKey.MATCH_ALL.equals(k)) {
            return SymbolId_matchAll;
        }
        if (SymbolKey.SEARCH.equals(k)) {
            return SymbolId_search;
        }
        return 0;
    }

    @Override
    protected int findPrototypeId(String s) {
        int id;
        switch (s) {
            case "compile":
                id = Id_compile;
                break;
            case "toString":
                id = Id_toString;
                break;
            case "toSource":
                id = Id_toSource;
                break;
            case "exec":
                id = Id_exec;
                break;
            case "test":
                id = Id_test;
                break;
            case "prefix":
                id = Id_prefix;
                break;
            default:
                id = 0;
                break;
        }
        return id;
    }

    private static final int Id_compile = 1,
            Id_toString = 2,
            Id_toSource = 3,
            Id_exec = 4,
            Id_test = 5,
            Id_prefix = 6,
            SymbolId_match = 7,
            SymbolId_matchAll = 8,
            SymbolId_search = 9,
            MAX_PROTOTYPE_ID = SymbolId_search;

    private RECompiled re;
    Object lastIndex = ScriptRuntime.zeroObj; /* index after last match, for //g iterator */
    private int lastIndexAttr = DONTENUM | PERMANENT;
} // class NativeRegExp
