"""Snapshot accessible POSIX locks using F_GETLK when procfs denies its table."""
import ctypes
import fcntl
import os
from pathlib import Path
import stat

class Flock(ctypes.Structure):
    _fields_=[('type',ctypes.c_short),('whence',ctypes.c_short),('start',ctypes.c_longlong),
              ('length',ctypes.c_longlong),('pid',ctypes.c_int)]

seen=set()
records=set()
uid=os.getuid()
for process in Path('/proc').iterdir():
    if not process.name.isdecimal():continue
    try:
        if process.stat().st_uid!=uid:continue
        for opened in (process/'fd').iterdir():
            try:
                info=opened.stat()
                key=(info.st_dev,info.st_ino)
                if not stat.S_ISREG(info.st_mode) or key in seen:continue
                fd=os.open(opened,os.O_RDONLY|os.O_CLOEXEC|os.O_NONBLOCK)
                try:
                    actual=os.fstat(fd)
                    if (actual.st_dev,actual.st_ino)!=key:continue
                    seen.add(key)
                    offset=0
                    for _ in range(32):
                        query=Flock(fcntl.F_WRLCK,os.SEEK_SET,offset,0,0)
                        result=Flock.from_buffer_copy(fcntl.fcntl(fd,fcntl.F_GETLK,bytes(query)))
                        if result.type==fcntl.F_UNLCK:break
                        if result.pid>0:
                            records.add((result.pid,info.st_dev,info.st_ino,result.type,result.start,result.length))
                        if result.length<=0:break
                        offset=result.start+result.length
                finally:os.close(fd)
            except (OSError,ValueError):continue
    except (OSError,ValueError):continue
for number,(pid,device,inode,kind,start,length) in enumerate(sorted(records),1):
    end=str(start+length-1) if length else 'EOF'
    print(f'{number}: POSIX ADVISORY {"READ" if kind==fcntl.F_RDLCK else "WRITE"} {pid} '
          f'{os.major(device):02x}:{os.minor(device):02x}:{inode} {start} {end}')
