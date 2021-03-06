/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.crsh.lang.groovy.closure;

import groovy.lang.Closure;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingMethodException;
import groovy.lang.MissingPropertyException;
import groovy.lang.Tuple;
import org.codehaus.groovy.runtime.MetaClassHelper;
import org.crsh.command.CommandCreationException;
import org.crsh.command.CommandInvoker;
import org.crsh.command.InvocationContext;
import org.crsh.command.ShellCommand;
import org.crsh.util.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/** @author Julien Viet */
public class PipeLineClosure extends Closure {

  /** . */
  private static final Object[] EMPTY_ARGS = new Object[0];

  /** . */
  private final InvocationContext<Object> context;

  /** . */
  private PipeLineElement[] elements;

  public PipeLineClosure(InvocationContext<Object> context, String name, ShellCommand command) {
    this(context, new CommandElement[]{new CommandElement(name, command, null)});
  }

  public PipeLineClosure(InvocationContext<Object> context, PipeLineElement[] elements) {
    super(new Object());

    //
    this.context = context;
    this.elements = elements;
  }

  public Object find() {
    return _gdk("find", EMPTY_ARGS);
  }

  public Object find(Closure closure) {
    return _gdk("find", new Object[]{closure});
  }

  private Object _gdk(String name, Object[] args) {
    PipeLineClosure find = _sub(name);
    if (find != null) {
      return find.call(args);
    } else {
      throw new MissingMethodException(name, PipeLineClosure.class, args);
    }
  }

  public Object or(Object t) {
    if (t instanceof PipeLineClosure) {
      PipeLineClosure next = (PipeLineClosure)t;
      PipeLineElement[] combined = Arrays.copyOf(elements, elements.length + next.elements.length);
      System.arraycopy(next.elements, 0, combined, elements.length, next.elements.length);
      return new PipeLineClosure(context, combined);
    } else if (t instanceof Closure) {
      Closure closure = (Closure)t;
      PipeLineElement[] combined = new PipeLineElement[elements.length + 1];
      System.arraycopy(elements, 0, combined, 0, elements.length);
      combined[elements.length] = new ClosureElement(closure);
      return new PipeLineClosure(context, combined);
    } else {
      throw new UnsupportedOperationException("Not supported");
    }
  }

  private PipeLineClosure _sub(String name) {
    if (elements.length == 1) {
      CommandElement element = (CommandElement)elements[0];
      if (element.name == null) {
        return new PipeLineClosure(context, new CommandElement[]{
            new CommandElement(element.commandName + "." + name, element.command, name)
        });
      }
    }
    return null;
  }

  public Object getProperty(String property) {
    try {
      return super.getProperty(property);
    }
    catch (MissingPropertyException e) {
      PipeLineClosure sub = _sub(property);
      if (sub != null) {
        return sub;
      } else {
        throw e;
      }
    }
  }

  @Override
  public Object invokeMethod(String name, Object args) {
    try {
      return super.invokeMethod(name, args);
    }
    catch (MissingMethodException e) {
      PipeLineClosure sub = _sub(name);
      if (sub != null) {
        return sub.call((Object[])args);
      } else {
        throw e;
      }
    }
  }

  private static Object[] unwrapArgs(Object arguments) {
    if (arguments == null) {
      return MetaClassHelper.EMPTY_ARRAY;
    } else if (arguments instanceof Tuple) {
      Tuple tuple = (Tuple) arguments;
      return tuple.toArray();
    } else if (arguments instanceof Object[]) {
      return (Object[])arguments;
    } else {
      return new Object[]{arguments};
    }
  }

  private PipeLineClosure options(Map<String, ?> options, Object[] arguments) {

    //
    CommandElement first = (CommandElement)elements[0];
    Map<String, Object> firstOptions = first.options;
    List<Object> firstArgs = first.args;

    // We merge options
    if (options != null && options.size() > 0) {
      if (firstOptions == null) {
        firstOptions = new HashMap<String, Object>();
      } else {
        firstOptions = new HashMap<String, Object>(options);
      }
      for (Map.Entry<?, ?> arg : options.entrySet()) {
        firstOptions.put(arg.getKey().toString(), arg.getValue());
      }
    }

    // We replace arguments
    if (arguments != null) {
      firstArgs = new ArrayList<Object>(Arrays.asList(arguments));
    }

    //
    PipeLineElement[] ret = elements.clone();
    ret[0] = new CommandElement(first.commandName, first.command, first.name, firstOptions, firstArgs);
    return new PipeLineClosure(context, ret);
  }

  @Override
  public Object call(Object... args) {

    final Closure closure;
    int to = args.length;
    if (to > 0 && args[to - 1] instanceof Closure) {
      closure = (Closure)args[--to];
    } else {
      closure = null;
    }

    // Configure the command with the closure
    if (closure != null) {
      final HashMap<String, Object> closureOptions = new HashMap<String, Object>();
      GroovyObjectSupport delegate = new GroovyObjectSupport() {
        @Override
        public void setProperty(String property, Object newValue) {
          closureOptions.put(property, newValue);
        }
      };
      closure.setResolveStrategy(Closure.DELEGATE_ONLY);
      closure.setDelegate(delegate);
      Object ret = closure.call();
      Object[] closureArgs;
      if (ret != null) {
        if (ret instanceof Object[]) {
          closureArgs = (Object[])ret;
        }
        else if (ret instanceof Iterable) {
          closureArgs = Utils.list((Iterable)ret).toArray();
        }
        else {
          boolean use = true;
          for (Object value : closureOptions.values()) {
            if (value == ret) {
              use = false;
              break;
            }
          }
          // Avoid the case : foo { bar = "juu" } that will make "juu" as an argument
          closureArgs = use ? new Object[]{ret} : EMPTY_ARGS;
        }
      } else {
        closureArgs = EMPTY_ARGS;
      }
      return options(closureOptions, closureArgs);
    } else {
      PipeLineInvoker binding = bind(args);
      if (context != null) {
        try {
          binding.invoke(context);
          return null;
        }
        catch (Exception e) {
          return throwRuntimeException(e);
        }
      } else {
        return binding;
      }
    }
  }

  public PipeLineClosure bind(InvocationContext<Object> context) {
    return new PipeLineClosure(context, elements);
  }

  public PipeLineInvoker bind(Object args) {
    return bind(unwrapArgs(args));
  }

  public PipeLineInvoker bind(Object[] args) {
    return new PipeLineInvoker(this, args);
  }

  LinkedList<CommandInvoker> resolve2(Object[] args) throws CommandCreationException {

    // Resolve options and arguments
    CommandElement elt = (CommandElement)elements[0];
    Map<String, Object> invokerOptions = elt.options != null ? elt.options : Collections.<String, Object>emptyMap();
    List<Object> invokerArgs = elt.args != null ? elt.args : Collections.emptyList();
    if (args.length > 0) {
      Object first = args[0];
      int from;
      if (first instanceof Map<?, ?>) {
        from = 1;
        Map<?, ?> options = (Map<?, ?>)first;
        if (options.size() > 0) {
          invokerOptions = new HashMap<String, Object>(invokerOptions);
          for (Map.Entry<?, ?> option : options.entrySet()) {
            String optionName = option.getKey().toString();
            Object optionValue = option.getValue();
            invokerOptions.put(optionName, optionValue);
          }
        }
      } else {
        from = 0;
      }
      if (from < args.length) {
        invokerArgs = new ArrayList<Object>(invokerArgs);
        while (from < args.length) {
          Object o = args[from++];
          if (o != null) {
            invokerArgs.add(o);
          }
        }
      }
    }

    //
    CommandElement first = (CommandElement)elements[0];
    PipeLineElement[] a = elements.clone();
    a[0] = new CommandElement(first.commandName, first.command, first.name, invokerOptions, invokerArgs);

    //
    LinkedList<CommandInvoker> ret = new LinkedList<CommandInvoker>();
    for (PipeLineElement _elt : a) {
      ret.add(_elt.make());
    }

    //
    return ret;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0;i < elements.length;i++) {
      if (i > 0) {
        sb.append(" | ");
      }
      elements[i].toString(sb);
    }
    return sb.toString();
  }
}
