/*     ____ ____  ____ ____  ______                                     *\
**    / __// __ \/ __// __ \/ ____/    SOcos COmpiles Scala             **
**  __\_ \/ /_/ / /__/ /_/ /\_ \       (c) 2002, LAMP/EPFL              **
** /_____/\____/\___/\____/____/                                        **
**                                                                      **
** $Id$
\*                                                                      */

package scalac.symtab.classfile;

import scalac.*;
import scalac.util.*;
import scalac.symtab.*;
import java.io.*;
import java.util.*;

//todo: don't keep statics module in scope.

public class ClassfileParser implements ClassfileConstants {

    static final int CLASS_ATTR  = SOURCEFILE_ATTR
                                 | INNERCLASSES_ATTR
                                 | SYNTHETIC_ATTR
                                 | DEPRECATED_ATTR
                                 | META_ATTR
                                 | SCALA_ATTR;
    static final int METH_ATTR   = CODE_ATTR
                                 | EXCEPTIONS_ATTR
                                 | SYNTHETIC_ATTR
                                 | DEPRECATED_ATTR
                                 | META_ATTR;
    static final int FIELD_ATTR  = CONSTANT_VALUE_ATTR
                                 | SYNTHETIC_ATTR
                                 | DEPRECATED_ATTR
                                 | META_ATTR;

    protected Global global;
    protected AbstractFileReader in;
    protected Symbol c;
    protected Type ctype;
    protected Scope locals;
    protected Scope statics;
    protected Scope constr;
    protected JavaTypeFactory make;
    protected Signatures sigs;
    protected ConstantPool pool;
    protected AttributeParser attrib;
    protected Definitions defs;
    protected int phaseId;


    public ClassfileParser(Global global, AbstractFileReader in, Symbol c) {
        this.global = global;
        this.in = in;
        this.c = c;
	this.ctype = Type.TypeRef(c.owner().thisType(), c, Type.EMPTY_ARRAY);
        this.make = new JavaTypeCreator(global);
        this.sigs = new Signatures(global, make);
        this.pool = new ConstantPool(in, sigs);
        this.attrib = new AttributeParser(in, pool, this);
        this.defs = global.definitions;
	this.phaseId = global.POST_ANALYZER_PHASE_ID;
    }


    /** parse the classfile and throw IO exception if there is an
     *  error in the classfile structure
     */
    public void parse() throws IOException {
        try {
            if (in.nextInt() != JAVA_MAGIC)
                throw new IOException("illegal start of class file");
            int minorVersion = in.nextChar();
            int majorVersion = in.nextChar();
            if ((majorVersion < JAVA_MAJOR_VERSION) ||
                ((majorVersion == JAVA_MAJOR_VERSION) &&
                 (minorVersion < JAVA_MINOR_VERSION)))
                throw new IOException("class file has wrong version " +
                        majorVersion + "." + minorVersion + ", should be " +
                        JAVA_MAJOR_VERSION + "." + JAVA_MINOR_VERSION);
            pool.indexPool();
            int flags = in.nextChar();
            Name name = readClassName(in.nextChar());
            if (c.fullName() != name)
                throw new IOException("class file '" + c.fullName() +
                                      "' contains wrong class " + name);
            // todo: correct flag transition
            c.flags = transFlags(flags);
	    if ((c.flags & Modifiers.ABSTRACT) != 0)
		c.flags = c.flags & ~Modifiers.ABSTRACT | Modifiers.ABSTRACTCLASS;
            Type supertpe = readClassType(in.nextChar());
            Type[] basetpes = new Type[in.nextChar() + 1];
            this.locals = new Scope();
            this.statics = new Scope();
            this.constr = new Scope();
            // set type of class
            Type classType = Type.compoundType(basetpes, locals, c);
            c.setInfo(classType, phaseId);
            // set type of statics
	    Symbol staticsClass = c.module().moduleClass();
	    Type staticsInfo = Type.compoundType(Type.EMPTY_ARRAY, statics, staticsClass);
            staticsClass.setInfo(staticsInfo, phaseId);
	    c.module().setInfo(Type.TypeRef(staticsClass.owner().thisType(),
					    staticsClass, Type.EMPTY_ARRAY));
            basetpes[0] = supertpe;
            for (int i = 1; i < basetpes.length; i++)
                basetpes[i] = readClassType(in.nextChar());
            int fieldCount = in.nextChar();
            for (int i = 0; i < fieldCount; i++)
                parseField();
            int methodCount = in.nextChar();
            for (int i = 0; i < methodCount; i++)
                parseMethod();
            // set constructor type to the declared type
	    Symbol[] constrs = constr.elements();
	    if (constrs.length != 0) {
		assert constrs.length == 1;
		c.constructor().setInfo(constrs[0].info(), phaseId);
	    } else {
		Type constrtype = ((c.flags & Modifiers.INTERFACE) != 0)
		    ? Type.PolyType(Symbol.EMPTY_ARRAY, ctype)
		    : Type.MethodType(new Symbol[]{Symbol.NONE}, ctype);
		c.constructor().setInfo(constrtype, phaseId);
            }
            attrib.readAttributes(c, classType, CLASS_ATTR);
	    //System.out.println("dynamic class: " + c);
	    //System.out.println("statics class: " + staticsClass);
	    //System.out.println("module: " + c.module());
	    //System.out.println("modules class: " + c.module().type().symbol());
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new IOException("bad class file (" + e.getMessage() + ")");
        }
    }

    /** convert Java modifiers into Scala flags
     */
    public int transFlags(int flags) {
        int res = 0;
        if (((flags & 0x0007) == 0) ||
            ((flags & 0x0002) != 0))
            res |= Modifiers.PRIVATE;
        else if ((flags & 0x0004) != 0)
            res |= Modifiers.PROTECTED;
        if ((flags & 0x0400) != 0)
            res |= Modifiers.ABSTRACT;
        if ((flags & 0x0010) != 0)
            res |= Modifiers.FINAL;
        if ((flags & 0x0200) != 0)
            res |= Modifiers.INTERFACE | Modifiers.ABSTRACTCLASS;
        return res | Modifiers.JAVA;
    }

    /** read a class name
     */
    protected Name readClassName(int i) {
        return (Name)pool.readPool(i);
    }

    /** read a class name and return the corresponding class type
     */
    protected Type readClassType(int i) {
        if (i == 0)
            return defs.ANY_TYPE;
        Type res = defs.getJavaType((Name)pool.readPool(i));
        if (res == Type.ErrorType)
            global.error("unknown class reference " + pool.readPool(i));
        if (res.symbol() == defs.JAVA_OBJECT_TYPE.symbol())
            return defs.ANYREF_TYPE;
        else
            return res;
    }

    /** read a signature and return it as a type
     */
    protected Type readType(int i) {
        Name sig = pool.readExternal(i);
        return sigs.sigToType(Name.names, sig.index, sig.length());
    }

    /** read a field
     */
    protected void parseField() {
        int flags = in.nextChar();
        Name name = (Name)pool.readPool(in.nextChar());
        Type type = readType(in.nextChar());
	int mods = transFlags(flags);
	if ((flags & 0x0010) == 0)
	    mods |= Modifiers.MUTABLE;
	Symbol owner = c;
	if ((flags & 0x0008) != 0)
	    owner = c.module().moduleClass();
        Symbol s = new TermSymbol(Position.NOPOS, name, owner, mods);
        s.setInfo(type, phaseId);
        attrib.readAttributes(s, type, FIELD_ATTR);
	((flags & 0x0008) != 0 ? statics : locals).enterOrOverload(s);
    }

    /** read a method
     */
    protected void parseMethod() {
        int flags = in.nextChar();
        Name name = (Name)pool.readPool(in.nextChar());
        Type type = readType(in.nextChar());
        if (CONSTR_N.equals(name)) {
            Symbol s = TermSymbol.newConstructor(c, transFlags(flags));
            // kick out protected, package visible or
            // private constructors
            if (((flags & 0x0004) != 0) ||
                ((flags & 0x0002) != 0) ||
                ((flags & 0x0007) == 0)) {
                attrib.readAttributes(s, type, METH_ATTR);
                return;
            }
            switch (type) {
	    case MethodType(Symbol[] vparams, _):
		if (c == defs.OBJECT_CLASS)
		    type = Type.PolyType(Symbol.EMPTY_ARRAY, ctype);
		else
		    type = Type.MethodType(vparams, ctype);
		break;
	    default:
		throw new ApplicationError();
	    }
	    s.setInfo(type, phaseId);
            attrib.readAttributes(s, type, METH_ATTR);
            //System.out.println("-- enter " + s);
            constr.enterOrOverload(s);
        } else {
	    Symbol s = new TermSymbol(
		Position.NOPOS,	name,
		((flags & 0x0008) != 0) ? c.module().moduleClass() : c,
		transFlags(flags));
	    s.setInfo(type, phaseId);
	    attrib.readAttributes(s, type, METH_ATTR);
	    ((flags & 0x0008) != 0 ? statics : locals).enterOrOverload(s);
	}
    }
}
