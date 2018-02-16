package edu.nps.moves.xmlpg;

import java.io.*;
import java.util.*;

/*
 * Generates the Qt c++ language source code files needed to read and write a protocol described
 * by an XML file. This is a counterpart to the JavaGenerator. This should generate .h and
 * .cpp files with ivars, getters, setters, marshaller, unmarshaler, constructors, and
 * destructors.
 *
 * Dale Marchand modified the CppGenerator to product Qt-ish c++ classes.
 *
 * @author DPM
 */

public class QtCppGenerator extends Generator
{
  /**
   * ivars are often preceded by a special character. This sets what that character is, 
   * so that instance variable names will be preceded by a "_".
   */
  public static final String IVAR_PREFIX = "m_";
  
  public static final String LIBRARY_HEADER = "libHdr";
  
  public static final String INDENT = "  ";

  /** Maps the primitive types listed in the XML file to the cpp types */
  Properties types = new Properties();

  /** What primitive types should be marshalled as. This may be different from
   * the cpp get/set methods, ie an unsigned short might have ints as the getter/setter,
   * but is marshalled as a short.
   */
  Properties marshalTypes = new Properties();

  /** sizes of various primitive types */
  Properties primitiveSizes = new Properties();

  /** A property list that contains cpp-specific code generation information, such
   * as package names, includes, etc.
   */
  Properties qtCppProperties;

  public QtCppGenerator(HashMap<?, ?> pClassDescriptions, Properties pQtCppProperties)
  {
    super(pClassDescriptions, pQtCppProperties);

    Properties systemProperties = System.getProperties();
    String clDirectory = systemProperties.getProperty("xmlpg.generatedSourceDir");

    // Directory to place generated source code
    if(clDirectory != null)
      pQtCppProperties.setProperty("directory", clDirectory);

    setDirectory(pQtCppProperties.getProperty("directory"));

    // Set up a mapping between the strings used in the XML file and the strings used
    // in the java file, specifically the data types. This could be externalized to
    // a properties file, but there's only a dozen or so and an external props file
    // would just add some complexity.
    types.setProperty("unsigned short", "quint16");
    types.setProperty("unsigned byte", "quint8");
    types.setProperty("unsigned int", "quint32");
    types.setProperty("unsigned long", "quint64");

    types.setProperty("byte", "qint8");
    types.setProperty("short", "qint16");
    types.setProperty("int", "qint32");
    types.setProperty("long", "qint64");

    types.setProperty("double", "double");
    types.setProperty("float", "float");

    // Set up the mapping between primitive types and marshal types.

    marshalTypes.setProperty("unsigned short", "quint16");
    marshalTypes.setProperty("unsigned byte", "quint8");
    marshalTypes.setProperty("unsigned int", "quint32");
    marshalTypes.setProperty("unsigned long", "quint64");

    marshalTypes.setProperty("byte", "qint8");
    marshalTypes.setProperty("short", "qint16");
    marshalTypes.setProperty("int", "qint32");
    marshalTypes.setProperty("long", "qint64");

    marshalTypes.setProperty("double", "double");
    marshalTypes.setProperty("float", "float");

    // How big various primitive types are
    primitiveSizes.setProperty("unsigned short", "2");
    primitiveSizes.setProperty("unsigned byte", "1");
    primitiveSizes.setProperty("unsigned int", "4");
    primitiveSizes.setProperty("unsigned long", "8");

    primitiveSizes.setProperty("byte", "1");
    primitiveSizes.setProperty("short", "2");
    primitiveSizes.setProperty("int", "4");
    primitiveSizes.setProperty("long", "8");

    primitiveSizes.setProperty("double", "8");
    primitiveSizes.setProperty("float", "4");
  }

  /**
   * Generates the cpp source code classes
   */
  public void writeClasses()
  {
    createDirectory();

    writeMacroFile();

    Iterator<?> it = classDescriptions.values().iterator();

    // Loop through all the class descriptions, generating a header file and cpp file for each.
    while(it.hasNext())
    {
      try
      {
        GeneratedClass aClass = (GeneratedClass)it.next();
        this.writeHeaderFile(aClass);
        this.writeCppFile(aClass);
      }
      catch(Exception e)
      {
        System.out.println("error creating source code " + e);
      }
    } // End while
  }

  /**
   * Use the Qt macros to set the import/export compiler directives.
   */
  public void writeMacroFile()
  {
    System.out.println("Creating microsoft library macro file");

    String headerFile = LIBRARY_HEADER;

    try
    {
      String headerFullPath = getDirectory() + "/" + headerFile + ".h";
      File outputFile = new File(headerFullPath);
      outputFile.createNewFile();
      PrintWriter pw = new PrintWriter(outputFile);

      pw.println("#ifndef " + headerFile.toUpperCase() + "_H");
      pw.println("#define " + headerFile.toUpperCase() + "_H");
      pw.println();
      pw.println("#include <QtGlobal>");
      pw.println();
      pw.println("#" + INDENT + "ifdef EXPORT_LIBRARY");
      pw.println("#" + INDENT + INDENT + "define EXPORT_MACRO Q_DECL_EXPORT");
      pw.println("#" + INDENT + "else");
      pw.println("#" + INDENT + INDENT + "define EXPORT_MACRO Q_DECL_IMPORT");
      pw.println("#" + INDENT + "endif");
      pw.println("#endif");

      pw.flush();
      pw.close();
    }
    catch(Exception e)
    {
      System.out.println(e);
    }
  }

  private boolean hasVariableLengthList(GeneratedClass aClass)
  {
    for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
    {
      if(((ClassAttribute) aClass.getClassAttributes().get(idx)).getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        return true;
    }
    return false;
  }
  
  /**
   * Generate a c++ header file for the classes
   */
  public void writeHeaderFile(GeneratedClass aClass)
  {
    try
    {
      String name = aClass.getName();
      //System.out.println("Creating cpp and .h source code files for " + name);
      String headerFullPath = getDirectory() + "/" + name + ".h";
      File outputFile = new File(headerFullPath);
      outputFile.createNewFile();
      PrintWriter pw = new PrintWriter(outputFile);

      // Write the usual #ifdef to prevent multiple inclusions by the preprocessor
      pw.println("#ifndef " + aClass.getName().toUpperCase() + "_H");
      pw.println("#define " + aClass.getName().toUpperCase() + "_H");
      pw.println();

      // Write includes for any classes we may reference. this generates multiple #includes if we
      // use a class multiple times, but that's innocuous. We could sort and do a unqiue to prevent
      // this if so inclined.

      String namespace = languageProperties.getProperty("namespace");
      if(namespace == null)
        namespace = "";
      else
        namespace = namespace + "/";

      boolean hasVariableLengthList = false;

      Set<String> includedTypes = new HashSet<String>();

      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);
        String type = anAttribute.getType();

        // If this attribute is a class, we need to do an import on that class
        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        { 
          if( ! includedTypes.contains(type) )
          {
            includedTypes.add(type);
            pw.println("#include <" + namespace + type + ".h>");
          }
        }

        // if this attribute is a variable-length list that holds a class, we need to
        // do an import on the class that is in the list.
        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        { 
          if( ! includedTypes.contains(type) )
          {
            includedTypes.add(type);
            pw.println("#include <" + namespace + type + ".h>");
          }

          hasVariableLengthList = true;
        }
      }

      if(hasVariableLengthList == true)
      {
        pw.println("#include <QList>");
      }

      // if we inherit from another class we need to do an include on it
      if(!(aClass.getParentClass().equalsIgnoreCase("root")))
      {
        pw.println("#include <" + namespace + aClass.getParentClass() + ".h>");
      }

      String msMacroFile = LIBRARY_HEADER;

      if(msMacroFile != null)
      {
        pw.println("#include \"" + namespace + msMacroFile + ".h\"");
      }

      pw.println();

      pw.println("class QDataStream;"); //Forward declaration of DataStream.h

      pw.println();

      // Print out namespace, if any
      namespace = languageProperties.getProperty("namespace");
      if(namespace != null)
      {
        pw.println("namespace " + namespace);
        pw.println("{");
      }


      // Print out the class comments, if any
      if(aClass.getClassComments() != null)
      {
        pw.println("// " + aClass.getClassComments() );
      }

      pw.println();
      pw.println("// Copyright (c) 2007-2012, MOVES Institute, Naval Postgraduate School. All rights reserved. ");
      pw.println("// Licensed under the BSD open source license. See http://www.movesinstitute.org/licenses/bsd.html");
      pw.println("//");
      pw.println("// @author DMcG, jkg");
      pw.println();

      // Print out class header and ivars

      if(aClass.getParentClass().equalsIgnoreCase("root"))
        pw.println("class EXPORT_MACRO " + aClass.getName());
      else
        pw.println("class EXPORT_MACRO " + aClass.getName() + " : public " + aClass.getParentClass());

      pw.println("{");

      // Print out ivars. These are made protected for now.
      pw.println("protected:");

      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
        { 
          if(anAttribute.getComment() != null)
            pw.println(INDENT + "/** " + anAttribute.getComment() + " */");

          pw.println(INDENT + types.get(anAttribute.getType()) + INDENT + IVAR_PREFIX + anAttribute.getName() + "; ");
          pw.println();

        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        { 
          if(anAttribute.getComment() != null)
            pw.println(INDENT + "/** " + anAttribute.getComment() + " */");

          pw.println(INDENT + anAttribute.getType() + " " + IVAR_PREFIX + anAttribute.getName() + "; ");
          pw.println();
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
        { 
          if(anAttribute.getComment() != null)
            pw.println(INDENT + "/** " + anAttribute.getComment() + " */");

          pw.println(INDENT + types.get(anAttribute.getType()) + " " + IVAR_PREFIX + anAttribute.getName() + "[" + anAttribute.getListLength() + "]; ");
          pw.println();
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        { 
          if(anAttribute.getComment() != null)
            pw.println(INDENT + "/** " + anAttribute.getComment() + " */");

          pw.println("  QList<" + anAttribute.getType() + "> " + IVAR_PREFIX + anAttribute.getName() + "; ");
          pw.println();
        }
      }


      // Delcare ctor and dtor in the public area
      pw.println("\n public:");
      // Constructor
      pw.println(INDENT + aClass.getName() + "();");


      // Destructor
      pw.println(INDENT + "virtual ~" + aClass.getName() + "() {}");
      pw.println();


      // Marshal and unmarshal methods
      pw.println(INDENT + "virtual void marshal(QDataStream& dataStream) const;");
      pw.println(INDENT + "virtual void unmarshal(QDataStream& dataStream);");
      pw.println();

      // Getter and setter methods for each ivar
      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
        { 
          pw.println(INDENT + types.get(anAttribute.getType()) + " " + "get" + this.initialCap(anAttribute.getName()) + "() const; ");
          if(anAttribute.getIsDynamicListLengthField() == false)
          {
            pw.println(INDENT + "void " + "set" + this.initialCap(anAttribute.getName()) + "(" + types.get(anAttribute.getType()) + " pX); ");
          }
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        { 
          pw.println(INDENT + anAttribute.getType() + "& " + "get" + this.initialCap(anAttribute.getName()) + "(); ");
          pw.println(INDENT + "const " + anAttribute.getType() + "&  get" + this.initialCap(anAttribute.getName()) + "() const; ");
          pw.println(INDENT + "void set" + this.initialCap(anAttribute.getName()) + "(const " + anAttribute.getType() + " &pX);");
          if(anAttribute.getCouldBeString() == true)
          {
            pw.println(INDENT + "QString get" + this.initialCap(anAttribute.getName()) + "() const;");
          }
        } 

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
        { 
          // Sleaze. We need to figure out what type of array we are, and this is slightly complex. 
          String arrayType = this.getArrayType(anAttribute.getType());
          pw.println(INDENT + arrayType + "* get" + this.initialCap(anAttribute.getName()) + "(); ");
          pw.println(INDENT + "const " + arrayType + "* get" + this.initialCap(anAttribute.getName()) + "() const; ");
          pw.println(INDENT + "void set" + this.initialCap(anAttribute.getName()) + "(const " + arrayType + "* pX);");
          if(anAttribute.getCouldBeString() == true)
          {
            pw.println(INDENT + "void " + "set" + this.initialCap(anAttribute.getName()) + "(const QString& pX);");
          }
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        { 
          pw.println(INDENT + "QList<" + anAttribute.getType() + ">& " + "get" + this.initialCap(anAttribute.getName()) + "(); ");
          pw.println(INDENT + "const QList<" + anAttribute.getType() + ">& " + "get" + this.initialCap(anAttribute.getName()) + "() const; ");
          pw.println(INDENT + "void set" + this.initialCap(anAttribute.getName()) + "(const QList<" + anAttribute.getType() + ">& pX);");
        }
      }    

      // Generate a getMarshalledSize() method header
      pw.println();
      pw.println(INDENT + "virtual int getMarshalledSize() const;");
      pw.println();

      // Generate an equality and inequality operator 
      pw.println(INDENT + "bool operator==(const " + aClass.getName() + "& rhs) const;");
      pw.println(INDENT + "bool operator!=(const " + aClass.getName() + "& rhs) const;");

      pw.println("};");

      // Close out namespace brace, if any
      if(namespace != null)
      {
        pw.println("}");
      }

      // Close if #ifndef statement that prevents multiple #includes
      pw.println("\n#endif");

      this.writeLicenseNotice(pw);

      pw.flush();
      pw.close();
    } // End of try
    catch(Exception e)
    {
      System.out.println(e);
    }

  } // End write header file

  public void writeCppFile(GeneratedClass aClass)
  {
    try
    {
      String name = aClass.getName();
      //System.out.println("Creating cpp and .h source code files for " + name);
      String headerFullPath = getDirectory() + "/" + name + ".cpp";
      File outputFile = new File(headerFullPath);
      outputFile.createNewFile();
      PrintWriter pw = new PrintWriter(outputFile);

      String namespace = languageProperties.getProperty("namespace");
      if(namespace==null)
        namespace ="";
      else
        namespace=namespace +"/";

      pw.println("#include \"" + namespace + aClass.getName() + ".h\"");
      pw.println("#include <QtGlobal>");
      pw.println("#include <QDataStream>");
      if(hasVariableLengthList(aClass))
        pw.println("#include <QList>");

      namespace = languageProperties.getProperty("namespace");
      if(namespace != null)
      {
        pw.println();
        pw.println("using namespace " + namespace + ";\n");
      }

      pw.println();

      // Write ctor 
      this.writeCtor(pw, aClass);
      
      //dtor is in the header
//      this.writeDtor(pw, aClass);

      // Write the getter and setter methods for each of the attributes
      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);
        this.writeGetterMethod(pw, aClass, anAttribute);
        this.writeSetterMethod(pw, aClass, anAttribute);
      }

      // Write marshal and unmarshal methods
      this.writeMarshalMethod(pw, aClass);
      this.writeUnmarshalMethod(pw, aClass);

      // Write the comparision operators
      this.writeEqualityOperator(pw, aClass);
      this.writeInequalityOperator(pw, aClass);

      // Method to determine the marshalled length of the PDU
      this.writeGetMarshalledSizeMethod(pw, aClass);

      // License notice
      this.writeLicenseNotice(pw);

      pw.flush();
      pw.close();
    }
    catch(Exception e)
    {
      System.out.println(e);
    }
  }

  /**
   * Write the code for an equality operator. This allows you to compare
   * two objects for equality.
   * The code should look like
   * 
   * bool operator ==(const ClassName& rhs)
   * return (_ivar1==rhs._ivar1 && _var2 == rhs._ivar2 ...)
   *
   */
  public void writeEqualityOperator(PrintWriter pw, GeneratedClass aClass)
  {
    try
    {
      pw.println("bool " + aClass.getName() + "::operator==(const " + aClass.getName() + "& rhs) const");
      pw.println("{");

      // Handle the superclass, if any
      String parentClass = aClass.getParentClass();
      if(!(parentClass.equalsIgnoreCase("root")) )
      {
        pw.println(INDENT + "if( " + parentClass + "::operator!=(rhs) ) return false;");
        pw.println();
      }

      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE || anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        {
          if(anAttribute.getIsDynamicListLengthField() == false)
          {
            pw.println(INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + " != rhs." + IVAR_PREFIX + anAttribute.getName() + " ) return false;");
            pw.println();
          }
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
        {
          pw.println(INDENT + "for(int idx = 0; idx < " + anAttribute.getListLength() + "; ++idx)");
          pw.println(INDENT + "{");
          pw.println(INDENT + INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + "[idx] != rhs." + IVAR_PREFIX + anAttribute.getName() + "[idx] ) return false;");
          pw.println(INDENT + "}");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        {
          pw.println(INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + ".size() != rhs.size() ) return false;");
          pw.println();
          pw.println(INDENT + "for(int idx = 0; idx < " + IVAR_PREFIX + anAttribute.getName() + ".size(); ++idx)");
          pw.println(INDENT + "{");
          pw.println(INDENT + INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + ".at(idx) != rhs." + IVAR_PREFIX + anAttribute.getName() + ".at(idx) ) return false;");
          pw.println(INDENT + "}");
        }

      }


      pw.println();
      pw.println(INDENT + "return true;");
      pw.println("}");
    }
    catch(Exception e)
    {
      System.out.println(e);
    }

  }

  /**
   * Write the code for an inequality operator. This allows you to compare
   * two objects for inequality.
   * The code should look like
   * 
   * bool operator !=(const ClassName& rhs)
   * return (_ivar1!=rhs._ivar1 || _var2 != rhs._ivar2 ...)
   *
   */
  public void writeInequalityOperator(PrintWriter pw, GeneratedClass aClass)
  {
    try
    {
      pw.println();
      pw.println("bool " + aClass.getName() + "::operator!=(const " + aClass.getName() + "& rhs) const");
      pw.println("{");

      // Handle the superclass, if any
      String parentClass = aClass.getParentClass();
      if(!(parentClass.equalsIgnoreCase("root")) )
      {
        pw.println(INDENT + "if( " + parentClass + "::operator!=(rhs) ) return true;");
        pw.println();
      }

      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE || anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        {
          if(anAttribute.getIsDynamicListLengthField() == false)
          {
            pw.println(INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + " != rhs." + IVAR_PREFIX + anAttribute.getName() + " ) return true;");
            pw.println();
          }
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
        {
          pw.println(INDENT + "for(int idx = 0; idx < " + anAttribute.getListLength() + "; ++idx)");
          pw.println(INDENT + "{");
          pw.println(INDENT + INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + "[idx] != rhs." + IVAR_PREFIX + anAttribute.getName() + "[idx] ) return true;");
          pw.println(INDENT + "}");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        {
          pw.println(INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + ".size() != rhs.size() ) return true;");
          pw.println();
          pw.println(INDENT + "for(int idx = 0; idx < " + IVAR_PREFIX + anAttribute.getName() + ".size(); ++idx)");
          pw.println(INDENT + "{");
          pw.println(INDENT + INDENT + "if( " + IVAR_PREFIX + anAttribute.getName() + ".at(idx) != rhs." + IVAR_PREFIX + anAttribute.getName() + ".at(idx) ) return true;");
          pw.println(INDENT + "}");
        }
      }

      pw.println();
      pw.println(INDENT + "return false;");
      pw.println("}");
    }
    catch(Exception e)
    {
      System.out.println(e);
    }

  }

  /**
   * Write the code for a method that marshals out the object into a DIS format
   * byte array.
   */
  public void writeMarshalMethod(PrintWriter pw, GeneratedClass aClass)
  {
    try
    {
      pw.println("void " + aClass.getName() + "::" + "marshal(QDataStream& dataStream) const");
      pw.println("{");

      // If this inherits from one of our classes, we should call the superclasses' 
      // marshal method first. The syntax for this is SuperclassName::marshal(dataStream).

      // If it's not already a root class
      if(!(aClass.getParentClass().equalsIgnoreCase("root")))
      {
        String superclassName = aClass.getParentClass();
        pw.println(INDENT + superclassName + "::marshal(dataStream); // Marshal information in superclass first");
      }


      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);

        if(anAttribute.shouldSerialize == false)
        {
          pw.println(INDENT + "// attribute " + anAttribute.getName() + " marked as do not serialize");
          continue;
        }

        // Write out the code to marshal this, depending on the type of attribute

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
        { 
          if(anAttribute.getIsDynamicListLengthField() == false)
          {
            pw.println(INDENT + "dataStream << " +  IVAR_PREFIX + anAttribute.getName() + ";");
          }
          else
          {
            ClassAttribute listAttribute = anAttribute.getDynamicListClassAttribute();
            pw.println(INDENT + "dataStream << static_cast<" + types.get(anAttribute.getType()) + ">(" +  IVAR_PREFIX + listAttribute.getName() + ".size());");
          }

        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        { 
          pw.println(INDENT + IVAR_PREFIX + anAttribute.getName() + ".marshal(dataStream);");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
        { 
          pw.println();
          pw.println(INDENT + "for(int idx = 0; idx < " + anAttribute.getListLength() + "; ++idx)");
          pw.println(INDENT + "{");

          // This is some sleaze. We're an array, but an array of what? We could be either a
          // primitive or a class. We need to figure out which. This is done via the expedient
          // but not very reliable way of trying to do a lookup on the type. If we don't find
          // it in our map of primitives to marshal types, we assume it is a class.

          String marshalType = marshalTypes.getProperty(anAttribute.getType());

          if(marshalType == null) // It's a class
          {
            pw.println(INDENT + INDENT + "const " +  IVAR_PREFIX + anAttribute.getName() + "[idx].marshal(dataStream);");
          }
          else
          {
            pw.println(INDENT + INDENT + "dataStream << " +  IVAR_PREFIX + anAttribute.getName() + "[idx];");
          }

          pw.println(INDENT + "}");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        { 
          pw.println();
          pw.println(INDENT + "for(int idx = 0; idx < " +  IVAR_PREFIX + anAttribute.getName() + ".size(); ++idx)");
          pw.println(INDENT + "{");

          String marshalType = marshalTypes.getProperty(anAttribute.getType());

          if(marshalType == null) // It's a class
          {
            pw.println(INDENT + INDENT + "const " + anAttribute.getType() + "& x = " +  IVAR_PREFIX + anAttribute.getName() + ".at(idx);");
            pw.println(INDENT + INDENT + "x.marshal(dataStream);");
          }
          else // it's a primitive
          {
            pw.println(INDENT + INDENT + "const " + anAttribute.getType() + " x = " +  IVAR_PREFIX + anAttribute.getName() + ".at(idx);");
            pw.println(INDENT + INDENT + "dataStream <<  x;"); 
          }

          pw.println(INDENT + " }");
        }
      }
      pw.println("}");
      pw.println();

    }
    catch(Exception e)
    {
      System.out.println(e);
    }
  }

  public void writeUnmarshalMethod(PrintWriter pw, GeneratedClass aClass)
  {
    try
    {
      pw.println("void " + aClass.getName() + "::" + "unmarshal(QDataStream& dataStream)");
      pw.println("{");

      // If it's not already a root class
      if(!(aClass.getParentClass().equalsIgnoreCase("root")))
      {
        String superclassName = aClass.getParentClass();
        pw.println(INDENT + superclassName + "::unmarshal(dataStream); // unmarshal information in superclass first");
      }

      for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
      {
        ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);

        if(anAttribute.shouldSerialize == false)
        {
          pw.println(INDENT + "// attribute " + anAttribute.getName() + " marked as do not serialize");
          continue;
        }

        // Write out the code to marshal this, depending on the type of attribute

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
        { 
          pw.println(INDENT + "dataStream >> " +  IVAR_PREFIX + anAttribute.getName() + ";");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
        { 
          pw.println(INDENT + IVAR_PREFIX + anAttribute.getName() + ".unmarshal(dataStream);");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
        { 
          pw.println();
          pw.println(INDENT + "for(int idx = 0; idx < " + anAttribute.getListLength() + "; ++idx)");
          pw.println(INDENT + "{");
          pw.println(INDENT + INDENT + "dataStream >> " +  IVAR_PREFIX + anAttribute.getName() + "[idx];");
          pw.println(INDENT + "}");
        }

        if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
        { 
          pw.println();
          pw.println(INDENT + IVAR_PREFIX + anAttribute.getName() + ".clear();"); // Clear out any existing objects in the list
          pw.println(INDENT + "for(size_t idx = 0; idx < " + IVAR_PREFIX + anAttribute.getCountFieldName() + "; ++idx)");
          pw.println(INDENT + "{");

          // This is some sleaze. We're an list, but an list of what? We could be either a
          // primitive or a class. We need to figure out which. This is done via the expedient
          // but not very reliable way of trying to do a lookup on the type. If we don't find
          // it in our map of primitives to marshal types, we assume it is a class.

          String marshalType = marshalTypes.getProperty(anAttribute.getType());

          if(marshalType == null) // It's a class
          {
            pw.println(INDENT + INDENT + anAttribute.getType() + " x;");
            pw.println(INDENT + INDENT + "x.unmarshal(dataStream);" );
            pw.println(INDENT + INDENT + IVAR_PREFIX + anAttribute.getName() + ".add(x);");
          }
          else // It's a primitive
          {
            pw.println(INDENT + INDENT + IVAR_PREFIX + anAttribute.getName() + "[idx] << dataStream");
          }

          pw.println(INDENT + "}");
        }
      }

      pw.println("}");
      pw.println();

    }
    catch(Exception e)
    {
      System.out.println(e);
    }
  }

  /** 
   * Write a constructor. This uses an initialization list to initialize the various object
   * ivars in the class. God, C++ is a PITA. The result should be something like
   * Foo::Foo() : bar(Bar(), baz(Baz()
   */
  private void writeCtor(PrintWriter pw, GeneratedClass aClass)
  {
    pw.print(aClass.getName() + "::" + aClass.getName() + "()");

    // Need to do a pre-flight here; cycle through the attributes and get a count
    // of the attributes that are either primitives or objects. The 
    // fixed length lists are not initialized in the initializer list.
    int attributeCount = 0;

    for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
    {
      ClassAttribute attribute = (ClassAttribute)aClass.getClassAttributes().get(idx);
      switch(attribute.getAttributeKind())
      {
        case PRIMITIVE:
        case CLASSREF:
        case FIXED_LIST:
        case VARIABLE_LIST:
          attributeCount++;
          default: break;
      }
    }

    boolean hasParentClass = !(aClass.getParentClass().equalsIgnoreCase("root"));
    
    //If there's something to initialize, construct an initialization list
    if(attributeCount > 0 || hasParentClass == true)
      pw.println(" : ");
    else
      pw.println();

    // If this has a superclass, class the constructor for that (via the initializer list)
    if(hasParentClass == true)
    {
      // Do an initialization list for the ctor    
      pw.print(INDENT + aClass.getParentClass() + "()");
      if(attributeCount > 0)
        pw.print(",");
      pw.println();
    }

    for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
    {
      ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);   

      // This is a primitive type; initialize it to either the default value specified in 
      // the XML file or to zero. Tends to minimize the possibility
      // of bad stack-allocated values hosing the system.
      if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
      { 

        String defaultValue = anAttribute.getDefaultValue();
        String initialValue = "0";
        String ivarType = anAttribute.getType();
        if( (ivarType.equalsIgnoreCase("float")) || (ivarType.equalsIgnoreCase("double")))
          initialValue = "0.0";

        if(defaultValue != null)
          initialValue = defaultValue;

        pw.print(INDENT + IVAR_PREFIX + anAttribute.getName() + "(" + initialValue + ")");

      }
      // We need to allcoate ivars that are objects....
      else if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
      { 
        // pw.print(" " + anAttribute.getName() + "( " + anAttribute.getType() + "())" );
        pw.print(INDENT + IVAR_PREFIX + anAttribute.getName() + "()" );
      }

      // We need to allcoate ivars that are lists/vectors....
      else if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
      { 
        // pw.print(" " + anAttribute.getName() + "( " + anAttribute.getType() + "())" );
        pw.print(INDENT + IVAR_PREFIX + anAttribute.getName() + "()" );
      }

      // We need to initialize primitive array types
      else if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
      { 
        pw.print(INDENT + IVAR_PREFIX + anAttribute.getName() + "{");
        List<String> initList = new ArrayList<String>();
        for(int i=0; i < anAttribute.getListLength(); ++i)
        {
          initList.add("0");
        }
        pw.print(String.join(",", initList));
        pw.print("}");
      }
      else
      {
        System.err.println("Unknown attribute type: " + anAttribute.getAttributeKind());
      }

      // Every initialization list element should have a following comma except the last
      attributeCount--;
      if(attributeCount != 0)
      {
        pw.println(", "); 
      }
    } // end of loop through attributes

    pw.println("\n{");

    // Set initial values
    List<?> inits = aClass.getInitialValues();
    for(int idx = 0; idx < inits.size(); ++idx)
    {
      InitialValue anInitialValue = (InitialValue)inits.get(idx);
      String setterName = anInitialValue.getSetterMethodName();
      pw.println(INDENT + setterName + "( " + anInitialValue.getVariableValue() + " );");
    }

    pw.println("}\n");
  }

  /**
   * Generate a destructor method, which deallocates objects
   */
  private void writeDtor(PrintWriter pw, GeneratedClass aClass)
  {
    pw.println(aClass.getName() + "::~" + aClass.getName() + "()");
    pw.println("{");

    //No, we don't need to explicitly deallocate objects in a QList.  
    //When the QList dtor is triggered, any objects inside the list will be deallocated
    //As long as we're not storing pointers, which we aren't.
//    for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
//    {
//      ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);
//
//      // We need to deallocate ivars that are objects....
//      if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
//      { 
//        pw.println(INDENT + INDENT +  IVAR_PREFIX + anAttribute.getName() + ".clear();");
//      } // end of if object
//    } // end of loop through attributes

    pw.println("}\n");
  }

  private void writeGetterMethod(PrintWriter pw, GeneratedClass aClass, ClassAttribute anAttribute)
  {
    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
    { 
      pw.println(types.get(anAttribute.getType()) + " " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() const");
      pw.println("{");
      if(anAttribute.getIsDynamicListLengthField() == false)
      {
        pw.println(INDENT + "return " +  IVAR_PREFIX + anAttribute.getName() + ";");
      }
      else
      {
        ClassAttribute listAttribute = anAttribute.getDynamicListClassAttribute();
        pw.println( INDENT + "return static_cast<quint32>(" +  IVAR_PREFIX + listAttribute.getName() + ".size());");
      }

      pw.println("}\n");
    }

    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
    { 
      pw.println(anAttribute.getType() + "& " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() ");
      pw.println("{");
      pw.println(INDENT + "return " +  IVAR_PREFIX + anAttribute.getName() + ";");
      pw.println("}\n");

      pw.println("const " + anAttribute.getType() + "& " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() const");
      pw.println("{");
      pw.println(INDENT + "return " +  IVAR_PREFIX + anAttribute.getName() + ";");
      pw.println("}\n");
    }

    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
    { 
      pw.println(this.getArrayType(anAttribute.getType()) + "* " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() ");
      pw.println("{");
      pw.println(INDENT + "return " +  IVAR_PREFIX + anAttribute.getName() + ";");
      pw.println("}\n");

      pw.println("const " + this.getArrayType(anAttribute.getType()) + "* " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() const");
      pw.println("{");
      pw.println(INDENT + "return " +  IVAR_PREFIX + anAttribute.getName() + ";");
      pw.println("}\n");
      
      if(anAttribute.getCouldBeString() == true)
      {
        pw.println("QString " + aClass.getName() + "::get" + this.initialCap(anAttribute.getName()) + "() const");
        pw.println("{");
        pw.println(INDENT + "return QString::fromLocal8Bit(" +  IVAR_PREFIX + anAttribute.getName() + ", " + anAttribute.getListLength() + ");");
        pw.println("}\n");
      }

    }

    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
    { 
      pw.println("QList<" + anAttribute.getType() + ">& " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() ");
      pw.println("{");
      pw.println(INDENT + "return " + IVAR_PREFIX +  anAttribute.getName() + ";");
      pw.println("}\n");

      pw.println("const QList<" + anAttribute.getType() + ">& " + aClass.getName()  +"::" + "get" + this.initialCap(anAttribute.getName()) + "() const");
      pw.println("{");
      pw.println(INDENT + "return " +  IVAR_PREFIX + anAttribute.getName() + ";");
      pw.println("}\n");
    }

    //pw.println(aClass.getName() + "::get" + aClass.getName() + "()");
    //pw.println("{");

  }

  public void writeSetterMethod(PrintWriter pw, GeneratedClass aClass, ClassAttribute anAttribute)
  {
    if((anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE) && (anAttribute.getIsDynamicListLengthField() == false))
    { 
      pw.println("void " + aClass.getName()  + "::" + "set" + this.initialCap(anAttribute.getName()) + "(" + types.get(anAttribute.getType()) + " pX)");
      pw.println("{");
      if(anAttribute.getIsDynamicListLengthField() == false)
        pw.println( INDENT + IVAR_PREFIX + anAttribute.getName() + " = pX;");
      pw.println("}\n");
    }

    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
    { 
      pw.println("void " + aClass.getName()  + "::" + "set" + this.initialCap(anAttribute.getName()) + "(const " + anAttribute.getType() + " &pX)");
      pw.println("{");
      pw.println( INDENT + IVAR_PREFIX + anAttribute.getName() + " = pX;");
      pw.println("}\n");
    }

    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
    { 
      pw.println("void " + aClass.getName()  + "::" + "set" + this.initialCap(anAttribute.getName()) + "(const " + this.getArrayType(anAttribute.getType()) + "* x)");
      pw.println("{");

      // The safest way to handle this is to set up a loop and individually copy over the array in a for loop. This makes
      // primitives and objects handling orthogonal, vs. doing a memcpy, which is faster but may or may not work.

      pw.println(INDENT + "for(int i = 0; i < " + anAttribute.getListLength() + "; i++)");
      pw.println(INDENT + "{");
      pw.println(INDENT + INDENT +  IVAR_PREFIX + anAttribute.getName() + "[i] = x[i];");
      pw.println(INDENT + "}");
      pw.println("}\n");

      // An alternative that is c-string friendly

      if(anAttribute.getCouldBeString() == true)
      {
        pw.println("// An alternate method to set the value if this could be a string. This is not strictly comnpliant with the DIS standard.");
        pw.println("void " + aClass.getName()  + "::" + "set" + this.initialCap(anAttribute.getName()) + "(const QString& x)");
        pw.println("{");
        pw.println(INDENT + "const int len = qMin(x.length(), " + anAttribute.getListLength() + ");");
        pw.println(INDENT + "if(len > 0)");
        pw.println(INDENT + INDENT + "memcpy(" + IVAR_PREFIX + anAttribute.getName() + ", qPrintable(x), len);");
        pw.println(INDENT + "const int rem = " + anAttribute.getListLength() + " - len;");
        pw.println(INDENT + "if(rem > 0)");
        pw.println(INDENT + INDENT + "memset(" + IVAR_PREFIX + anAttribute.getName() + " + len, 0, rem);");
        pw.println("}");
        pw.println();
      }
    }

    if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
    { 
      pw.println("void " + aClass.getName()  + "::" + "set" + this.initialCap(anAttribute.getName()) + "(const QList<" + anAttribute.getType() + ">& pX)");
      pw.println("{");
      pw.println( INDENT + " " +  IVAR_PREFIX + anAttribute.getName() + " = pX;");
      pw.println("}\n");
    }
  }

  public void writeGetMarshalledSizeMethod(PrintWriter pw, GeneratedClass aClass)
  {
    List<?> ivars = aClass.getClassAttributes();

    // Generate a getMarshalledLength() method header
    pw.println();
    pw.println("int " + aClass.getName()  + "::" + "getMarshalledSize() const");
    pw.println("{");
    pw.println(INDENT + "int marshalSize = 0;");
    pw.println();

    // Size of superclass is the starting point
    if(!aClass.getParentClass().equalsIgnoreCase("root"))
    {
      pw.println(INDENT + "marshalSize = " + aClass.getParentClass() + "::getMarshalledSize();");
    }

    for(int idx = 0; idx < ivars.size(); ++idx)
    {
      ClassAttribute anAttribute = (ClassAttribute)ivars.get(idx);

      if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.PRIMITIVE)
      {
        pw.print(INDENT + "marshalSize = marshalSize + ");
        pw.println(primitiveSizes.get(anAttribute.getType()) + ";  // " + IVAR_PREFIX + anAttribute.getName());
      }

      if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.CLASSREF)
      {
        pw.print(INDENT + "marshalSize = marshalSize + ");
        pw.println(IVAR_PREFIX + anAttribute.getName() + ".getMarshalledSize();  // " + IVAR_PREFIX + anAttribute.getName());
      }

      if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.FIXED_LIST)
      {
        pw.print(INDENT + "marshalSize = marshalSize + ");
        // If this is a fixed list of primitives, it's the list size times the size of the primitive.
        if(anAttribute.getUnderlyingTypeIsPrimitive() == true)
        {
          pw.println( anAttribute.getListLength() + " * " + primitiveSizes.get(anAttribute.getType()) + ";  // " + IVAR_PREFIX + anAttribute.getName());
        }
        else
        {
          //pw.println( anAttribute.getListLength() + " * " +  " new " + anAttribute.getType() + "().getMarshalledSize()"  + ";  // " + anAttribute.getName());
          pw.println(" THIS IS A CONDITION NOT HANDLED BY XMLPG: a fixed list array of objects. That's  why you got the compile error.");
        }
      }

      if(anAttribute.getAttributeKind() == ClassAttribute.ClassAttributeType.VARIABLE_LIST)
      {
        // If this is a dynamic list of primitives, it's the list size times the size of the primitive.
        if(anAttribute.getUnderlyingTypeIsPrimitive() == true)
        {
          pw.println( anAttribute.getName() + ".size() " + " * " + primitiveSizes.get(anAttribute.getType()) + ";  // " + IVAR_PREFIX + anAttribute.getName());
        }
        else
        {
          pw.println();
          pw.println(INDENT + "for(int idx=0; idx < " + IVAR_PREFIX + anAttribute.getName() + ".size(); ++idx)");
          pw.println(INDENT + "{");
          //pw.println( anAttribute.getName() + ".size() " + " * " +  " new " + anAttribute.getType() + "().getMarshalledSize()"  + ";  // " + anAttribute.getName());
          pw.println(INDENT + INDENT + "const " + anAttribute.getType() + "& listElement = " + IVAR_PREFIX + anAttribute.getName() + ".at(idx);");
          pw.println(INDENT + INDENT + "marshalSize = marshalSize + listElement.getMarshalledSize();");
          pw.println(INDENT + "}");
          pw.println();
        }
      }
    }
    pw.println();
    pw.println(INDENT + "return marshalSize;");
    pw.println("}");
    pw.println();
  }

  /** 
   * returns a string with the first letter capitalized. 
   */
  public String initialCap(String aString)
  {
    StringBuffer stb = new StringBuffer(aString);
    stb.setCharAt(0, Character.toUpperCase(aString.charAt(0)));

    return new String(stb);
  }

  /**
   * Returns true if this class consists only of instance variables that are
   * primitives, such as short, int, etc. Things that are not allowed include
   * ivars that are classes, arrays, or variable length lists. If a class
   * contains any of these, false is returned.
   */
  private boolean classHasOnlyPrimitives(GeneratedClass aClass)
  {
    boolean isAllPrimitive = true;

    // Flip flag to false if anything is not a primitive.
    for(int idx = 0; idx < aClass.getClassAttributes().size(); ++idx)
    {
      ClassAttribute anAttribute = (ClassAttribute)aClass.getClassAttributes().get(idx);
      if(anAttribute.getAttributeKind() != ClassAttribute.ClassAttributeType.PRIMITIVE)
      {
        isAllPrimitive = false;
        System.out.println("Not primitive for class " + aClass.getName() + " and attribute " + anAttribute.getName() + " " + anAttribute.getAttributeKind());
      }
    }

    return isAllPrimitive;
  }

  /**
   * Some code to figure out the characters to use for array types. We may have arrays of either primitives
   * or classes; this figures out which it is and returns the right string.
   */
  private String getArrayType(String xmlType)
  {
    String marshalType = marshalTypes.getProperty(xmlType);

    if(marshalType == null) // It's a class
    {
      return xmlType;
    }
    else // It's a primitive
    {
      return marshalType;
    }

  }

  private void writeLicenseNotice(PrintWriter pw)
  {
    pw.println("// Copyright (c) 1995-2009 held by the author(s).  All rights reserved.");

    pw.println("// Redistribution and use in source and binary forms, with or without");
    pw.println("// modification, are permitted provided that the following conditions");
    pw.println("//  are met:");
    pw.println("// ");
    pw.println("//  * Redistributions of source code must retain the above copyright");
    pw.println("// notice, this list of conditions and the following disclaimer.");
    pw.println("// * Redistributions in binary form must reproduce the above copyright");
    pw.println("// notice, this list of conditions and the following disclaimer");
    pw.println("// in the documentation and/or other materials provided with the");
    pw.println("// distribution.");
    pw.println("// * Neither the names of the Naval Postgraduate School (NPS)");
    pw.println("//  Modeling Virtual Environments and Simulation (MOVES) Institute");
    pw.println("// (http://www.nps.edu and http://www.MovesInstitute.org)");
    pw.println("// nor the names of its contributors may be used to endorse or");
    pw.println("//  promote products derived from this software without specific");
    pw.println("// prior written permission.");
    pw.println("// ");
    pw.println("// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS");
    pw.println("// AS IS AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT");
    pw.println("// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS");
    pw.println("// FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE");
    pw.println("// COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,");
    pw.println("// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,");
    pw.println("// BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;");
    pw.println("// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER");
    pw.println("// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT");
    pw.println("// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN");
    pw.println("// ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE");
    pw.println("// POSSIBILITY OF SUCH DAMAGE.");

  }


}
