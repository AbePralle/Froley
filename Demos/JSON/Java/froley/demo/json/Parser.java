package froley.demo.json;

import java.io.*;
import java.util.*;

public class Parser
{
  // DEFINITIONS
  final static public int VERSION     = 1;
  final static public int MIN_VERSION = 1;

  // PROPERTIES
  public String   filepath;
  public int[]    code;
  public String[] strings;
  public Token[]  tokens;

  public HashMap<String,Integer> methods          = new HashMap<String,Integer>();
  public HashMap<Integer,String> methodsByAddress = new HashMap<Integer,String>();

  public IntList callStack           = new IntList();
  public IntList methodStack         = new IntList();
  public IntList numberStack         = new IntList();
  public ArrayList<Token> tokenStack = new ArrayList<Token>();

  public ArrayList<String> varNames  = new ArrayList<String>();
  public IntList           varValues = new IntList();
  public IntList           varFrames = new IntList().add( 0 );

  public ArrayList<ParsePosition> savedPositions = new ArrayList<ParsePosition>();

  public ArrayList<Cmd>   cmdQueue     = new ArrayList<Cmd>();
  public CmdInitArgs      cmdArgs      = new CmdInitArgs();
  public ArrayList<Token> listStartT   = new ArrayList<Token>();
  public IntList          listStartPos = new IntList();

  public int   position;
  public int   nextTokenType;
  public Token curToken;

  public Tokenizer tokenizer = new Tokenizer();

  // METHODS
  public Parser()
  {
    load( Code.parserCode );
  }

  public void reset()
  {
    callStack.clear();
    methodStack.clear();
    tokenStack.clear();
    numberStack.clear();
    varNames.clear();
    varValues.clear();
    varFrames.clear().add(0);
    savedPositions.clear();
    listStartT.clear();
    listStartPos.clear();
    position = 0;
    curToken = null;
    nextTokenType = -1;
    tokenizer.reset();
  }

  public void execute( int ip )
  {
    curToken = peek();
    methodStack.add( ip );
    for (;;)
    {
      int opcode = code[ ip++ ];
      // System.out.println( StringUtility.format(ip,'0',4) + " " + opcode );
      switch (opcode)
      {
        case ParserOpcode.SYNTAX_ERROR:
          if (position == tokens.length)
          {
            throw peek().error( "Syntax error - unexpected end of input." );
          }
          else
          {
            Token t = tokens[ position ];
            String sym = StringUtility.quoted( TokenType.symbols[t.type] );
            throw t.error( "Syntax error - unexpected " + sym + "." );
          }
        case ParserOpcode.RETURN:
          {
            if (callStack.count == 0) return;
            ip = callStack.removeLast();
            methodStack.removeLast();
            curToken = tokenStack.remove( tokenStack.size() - 1 );
            int varCount = varFrames.removeLast();
            while (varNames.size() > varCount) varNames.remove( varNames.size()-1 );
            varValues.count = varCount;
            continue;
          }
        case ParserOpcode.CALL:
          callStack.add( ip+1 );
          tokenStack.add( curToken );
          curToken = peek();
          ip = code[ ip ];
          methodStack.add( ip );
          varFrames.add( varValues.count );
          continue;
        case ParserOpcode.JUMP:
          ip = code[ ip ];
          continue;
        case ParserOpcode.JUMP_IF_TRUE:
          if (numberStack.count>0 && 0!=numberStack.removeLast()) ip = code[ ip ];
          else                                                    ++ip;
          continue;
        case ParserOpcode.JUMP_IF_FALSE:
          if (numberStack.count>0 && 0==numberStack.removeLast()) ip = code[ ip ];
          else                                                    ++ip;
          continue;
        case ParserOpcode.ON_TOKEN_TYPE:
          if (code[ip] == nextTokenType)
          {
            ip += 2;
            curToken = read();
          }
          else
          {
            ip = code[ ip+1 ];
          }
          continue;
        case ParserOpcode.HAS_ANOTHER:
          numberStack.add( (nextTokenType!=-1)?1:0 );
          continue;
        case ParserOpcode.NEXT_HAS_ATTRIBUTE:
          numberStack.add( (nextTokenType != -1 && (TokenType.attributes[nextTokenType] & code[ip]) != 0) ? 1 : 0 );
          ++ip;
          continue;
        case ParserOpcode.NEXT_IS_TYPE:
          numberStack.add( (nextTokenType == code[ip++]) ? 1 : 0 );
          continue;
        case ParserOpcode.BEGIN_LIST:
          listStartT.add( peek() );
          listStartPos.add( cmdQueue.size() );
          continue;
        case ParserOpcode.CREATE_CMD:
        {
          int cmdTypeIndex = code[ ip++ ];
          int argCount = code[ ip++ ];
          cmdArgs.clear();
          if (argCount > 0)
          {
            int i1 = cmdQueue.size() - argCount;
            if (i1 < 0) throw curToken.error( "[INTERNAL] Command queue too small." );
            for (int i=i1; i<cmdQueue.size(); ++i)
            {
              cmdArgs.add( cmdQueue.get(i) );
            }
            while (cmdQueue.size() > i1) cmdQueue.remove( cmdQueue.size() - 1 );
          }
          cmdQueue.add( CmdFactory.createCmd(cmdTypeIndex,curToken,cmdArgs) );
          continue;
        }

        case ParserOpcode.CREATE_NULL_CMD:
          cmdQueue.add( null );
          continue;

        case ParserOpcode.CREATE_LIST:
        {
          if (listStartT.size() == 0) throw peek().error( "[INTERNAL] No prior beginList before calling createList/produceList." );
          Token t  = listStartT.remove( listStartT.size() - 1 );
          int   i1 = listStartPos.removeLast();
          cmdArgs.clear();
          for (int i=i1; i<cmdQueue.size(); ++i)
          {
            cmdArgs.add( cmdQueue.get(i) );
          }
          while (cmdQueue.size() > i1) cmdQueue.remove( cmdQueue.size() - 1 );
          cmdQueue.add( new Cmd.CmdList(curToken,cmdArgs) );
          continue;
        }

        case ParserOpcode.CREATE_STATEMENTS:
        {
          if (listStartT.size() == 0) throw peek().error( "[INTERNAL] No prior beginList before calling createStatements/produceStatements." );
          Token t  = listStartT.remove( listStartT.size() - 1 );
          int   i1 = listStartPos.removeLast();
          cmdArgs.clear();
          for (int i=i1; i<cmdQueue.size(); ++i)
          {
            cmdArgs.add( cmdQueue.get(i) );
          }
          while (cmdQueue.size() > i1) cmdQueue.remove( cmdQueue.size() - 1 );
          cmdQueue.add( new Cmd.CmdStatements(curToken,cmdArgs) );
          continue;
        }

        case ParserOpcode.CONSUME_EOLS:
          // Called to automatically consume EOL tokens that occur in the
          // midst of parsing a unary or binary operator, like "a+\nb".
          while (nextTokenType == TokenType.EOL) read();
          continue;

        case ParserOpcode.CONSUME_TYPE:
          if (nextTokenType == code[ip++])
          {
            read();
            numberStack.add( 1 );
          }
          else
          {
            numberStack.add( 0 );
          }
          continue;

        case ParserOpcode.MUST_CONSUME_TYPE:
          if (nextTokenType == code[ip++])
          {
            read();
            continue;
          }
          else
          {
            throw peek().error(
                "Expected " + StringUtility.quoted(TokenType.symbols[code[ip]]) +
                ", found " + ((nextTokenType!=-1) ? StringUtility.quoted(peek().toString()) : "end of input") + "." );
          }

        case ParserOpcode.SAVE_POSITION:
          savedPositions.add( new ParsePosition(position,cmdQueue.size(),curToken) );
          continue;

        case ParserOpcode.RESTORE_POSITION:
        {
          int count = savedPositions.size();
          if (count == 0) throw peek().error( "[INTERNAL] No savePosition to restore." );
          ParsePosition savedPosition = savedPositions.remove( count - 1 );
          position = savedPosition.position;
          int discardFrom = savedPosition.cmdCount;
          while (cmdQueue.size() > discardFrom) cmdQueue.remove( cmdQueue.size() - 1 );
          curToken = savedPosition.curToken;
          if (position < tokens.length) nextTokenType = tokens[position].type;
          else                          nextTokenType = -1;
          continue;
        }

        case ParserOpcode.DISCARD_SAVED_POSITION:
        {
          int count = savedPositions.size();
          if (count > 0) savedPositions.remove( count - 1 );
          continue;
        }

        case ParserOpcode.TRACE:
          System.out.print( "Line " );
          System.out.print( code[ip++] );
          System.out.print( " next:" );

          if (nextTokenType != -1) System.out.print( peek() );
          else                     System.out.print( "end of input" );
          System.out.print( " opcode:" );
          System.out.print( code[ip] );
          System.out.println();
          System.out.print( "  " );
          for (int index=0; index<methodStack.count; ++index)
          {
            if (index > 0) System.out.print( " > " );
            System.out.print( methodsByAddress.get( methodStack.get(index) ) );
          }
          System.out.println();
          System.out.print( "  [" );
          for (int index=0; index<cmdQueue.size(); ++index)
          {
            Cmd cmd = cmdQueue.get( index );
            if (index > 0) System.out.print( "," );
            System.out.print( (cmd!=null) ? cmd.getClass().getName() : "null" );
          }
          System.out.println( "]" );
          continue;

        case ParserOpcode.PRINTLN_STRING:
          System.out.println( strings[ code[ip++] ] );
          continue;

        case ParserOpcode.PRINTLN_NUMBER:
          System.out.println( numberStack.removeLast() );
          continue;

        case ParserOpcode.POP_DISCARD:
          numberStack.removeLast();
          continue;

        case ParserOpcode.PUSH_INT32:
          numberStack.add( code[ip++] );
          continue;

        case ParserOpcode.DECLARE_VAR:
        {
          String name = strings[ code[ip++] ];
          int index = locateVar( name, varFrames.last(), false );
          if (index != -1)
          {
            throw new Error( "A variable named '" + name + "' has already been declared in the current method." );
          }
          else
          {
            varNames.add( name );
            varValues.add( numberStack.removeLast() );
          }
          continue;
        }

        case ParserOpcode.WRITE_VAR:
        {
          String name = strings[ code[ip++] ];
          int index = locateVar( name, 0, true );
          varValues.set( index, numberStack.removeLast() );
          continue;
        }

        case ParserOpcode.READ_VAR:
        {
          String name = strings[ code[ip++] ];
          int index = locateVar( name, 0, true );
          numberStack.add( varValues.get(index) );
          continue;
        }

        case ParserOpcode.LOGICAL_NOT:
          numberStack.add( (numberStack.removeLast()==0) ? 1 : 0 );
          continue;

        case ParserOpcode.COMPARE_EQ:
        {
          int b = numberStack.removeLast();
          numberStack.add( (numberStack.removeLast()==b) ? 1 : 0 );
          continue;
        }

        case ParserOpcode.COMPARE_NE:
        {
          int b = numberStack.removeLast();
          numberStack.add( (numberStack.removeLast()!=b) ? 1 : 0 );
          continue;
        }

        case ParserOpcode.COMPARE_LT:
        {
          int b = numberStack.removeLast();
          numberStack.add( (numberStack.removeLast()<b) ? 1 : 0 );
          continue;
        }

        case ParserOpcode.COMPARE_LE:
        {
          int b = numberStack.removeLast();
          numberStack.add( (numberStack.removeLast()<=b) ? 1 : 0 );
          continue;
        }

        case ParserOpcode.COMPARE_GT:
        {
          int b = numberStack.removeLast();
          numberStack.add( (numberStack.removeLast()>b) ? 1 : 0 );
          continue;
        }

        case ParserOpcode.COMPARE_GE:
        {
          int b = numberStack.removeLast();
          numberStack.add( (numberStack.removeLast()>=b) ? 1 : 0 );
          continue;
        }

        default:
          throw new Error( "[INTERNAL] Unhandled parser opcode: " + opcode );
      }
    }
  }

  public boolean hasAnother()
  {
    return (position < tokens.length);
  }

  public void load( String data )
  {
    load( new Base64IntXReader(data) );
  }

  public void load( Base64IntXReader reader )
  {
    int version = reader.readInt32X();
    if (version < MIN_VERSION) throw new Error( "[INTERNAL] Unsupported version of Tokenizer." );

    int n = reader.readInt32X();
    strings = new String[ n ];
    for (int i=0; i<n; ++i)
    {
      strings[i] = reader.readString();
    }

    // Method names & addresses
    n = reader.readInt32X();
    for (int i=n; --i>=0; )
    {
      String name = strings[ reader.readInt32X() ];
      int mIP = reader.readInt32X();
      methods.put( name, mIP );
      methodsByAddress.put( mIP, name );
    }

    n = reader.readInt32X();
    code = new int[ n ];
    for (int i=0; i<n; ++i)
    {
      code[i] = reader.readInt32X();
    }
  }

  public int locateVar( String name, int lowestIndex, boolean mustLocate )
  {
    for (int index=varValues.count; --index>=lowestIndex; )
    {
      if (varNames.get(index).equals(name)) return index;
    }
    if (mustLocate) throw new Error( "[INTERNAL] No variable named '" + name + "' has been declared." );
    return -1;
  }

  public Cmd parse( String ruleName )
  {
    Integer mIP = methods.get( ruleName );
    if (mIP == null) throw new Error( "[INTERNAL] No parse rule '" + ruleName + "' exists." );
    return parse( mIP );
  }

  public Cmd parse( int address )
  {
    reset();
    execute( address );
    if (cmdQueue.size() == 0) return null;
    return cmdQueue.remove( cmdQueue.size() - 1 );
  }

  public Token peek()
  {
    if (position == tokens.length)
    {
      if (tokens.length == 0) return new Token( 0, null, filepath, "", 0, 0 );
      Token t = tokens[ tokens.length-1 ].cloned( TokenType.EOI );
      ++t.column;
      return t;
    }
    else
    {
      return tokens[ position ];
    }
  }

  public Token read()
  {
    if (++position < tokens.length) nextTokenType = tokens[position].type;
    else                           nextTokenType = -1;
    return tokens[ position-1 ];
  }

  public void open( String filepath, String content )
  {
    open( filepath, content, 1, 1 );
  }

  public void open( String filepath, String content, int startLine, int startColumn )
  {
    open( filepath, tokenizer.tokenize(filepath,content,startLine,startColumn) );
  }

  public void open( File file )
  {
    open( file.getPath(), tokenizer.tokenize(file) );
  }

  public void open( String filepath, Token[] tokens )
  {
    this.filepath = filepath;
    this.tokens = tokens;
    if (tokens.length > 0)
    {
      nextTokenType = tokens[0].type;
    }
    else
    {
      nextTokenType = -1;
    }
    position = 0;
  }

  static public class ParsePosition
  {
    public int   position;
    public int   cmdCount;
    public Token curToken;

    public ParsePosition( int position, int cmdCount, Token curToken )
    {
      this.position = position;
      this.cmdCount = cmdCount;
      this.curToken = curToken;
    }
  }
}
