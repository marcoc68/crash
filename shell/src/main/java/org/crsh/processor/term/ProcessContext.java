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

package org.crsh.processor.term;

import org.crsh.shell.ShellProcess;
import org.crsh.shell.ShellProcessContext;
import org.crsh.shell.ShellResponse;
import org.crsh.text.Chunk;
import org.crsh.term.TermEvent;
import org.crsh.text.Text;
import org.crsh.util.Safe;

import java.io.IOException;
import java.util.logging.Level;

class ProcessContext implements ShellProcessContext, Runnable {

  /** . */
  final Processor processor;

  /** . */
  final ShellProcess process;

  ProcessContext(Processor processor, ShellProcess process) {
    this.process = process;
    this.processor = processor;
  }

  public boolean takeAlternateBuffer() throws IOException {
    return processor.term.takeAlternateBuffer();
  }

  public boolean releaseAlternateBuffer() throws IOException {
    return processor.term.releaseAlternateBuffer();
  }

  public void run() {
    process.execute(this);
  }

  public int getWidth() {
    return processor.term.getWidth();
  }

  public int getHeight() {
    return processor.term.getHeight();
  }

  public String getProperty(String name) {
    return processor.term.getProperty(name);
  }

  public String readLine(String msg, boolean echo) {
    try {
      processor.term.write(Text.create(msg));
      processor.term.flush();
    }
    catch (IOException e) {
      return null;
    }
    boolean done = false;
    while (true) {
      synchronized (processor.lock) {
        switch (processor.status) {
          case CLOSED:
          case CANCELLING:
            return null;
          case PROCESSING:
            if (processor.queue.size() > 0) {
              TermEvent event = processor.queue.removeFirst();
              if (event instanceof TermEvent.ReadLine) {
                return ((TermEvent.ReadLine)event).getLine().toString();
              }
            }
            break;
          default:
            throw new AssertionError("Does not make sense " + processor.status);
        }
      }
      if (done) {
        return null;
      } else {
        done = true;
        processor.waitingEvent = true;
        try {
          processor.term.setEcho(echo);
          processor.readTerm();
          processor.term.write(Text.create("\r\n"));
        }
        catch (IOException e) {
          processor.log.log(Level.SEVERE, "Error when readline line");
        }
        finally {
          processor.waitingEvent = false;
          processor.term.setEcho(true);
        }
      }
    }
  }

  public Class<Chunk> getConsumedType() {
    return Chunk.class;
  }

  public void write(Chunk chunk) throws IOException {
    provide(chunk);
  }

  public void provide(Chunk element) throws IOException {
    processor.term.write(element);
  }

  public void flush() throws IOException {
    processor.term.flush();
  }

  public void end(ShellResponse response) {
    Runnable runnable;
    ProcessContext context;
    Status status;
    synchronized (processor.lock) {

      //
      processor.current = null;
      switch (processor.status) {
        case PROCESSING:
          if (response instanceof ShellResponse.Close) {
            runnable = processor.CLOSE;
            processor.status = Status.CLOSED;
          } else if (response instanceof ShellResponse.Cancelled) {
            runnable = Processor.NOOP;
            processor.status = Status.AVAILABLE;
          } else {
            final String message = response.getMessage();
            runnable = new Runnable() {
              public void run() {
                try {
                  processor.provide(Text.create(message));
                }
                catch (IOException e) {
                  // todo ???
                  e.printStackTrace();
                }
                finally {
                  // Be sure to flush
                  Safe.flush(processor.term);
                }
              }
            };
            processor.status = Status.AVAILABLE;
          }
          break;
        case CANCELLING:
          runnable = Processor.NOOP;
          processor.status = Status.AVAILABLE;
          break;
        default:
          throw new AssertionError("Does not make sense " + processor.status);
      }

      // Do we have a next process to execute ?
      context = processor.peekProcess();
      status = processor.status;
    }

    //
    runnable.run();

    //
    if (context != null) {
      context.run();
    } else if (status == Status.AVAILABLE) {
      processor.writePromptFlush();
    }
  }
}
