import ctypes
import datetime
import itertools
import os
import sys

from pyaloha.protocol import SerializableDatetime

try:
    from ctypes import set_conversion_mode
except ImportError:
    pass
else:
    set_conversion_mode('utf8', 'strict')


def c_unicode(c_data):
    return unicode(ctypes.string_at(c_data), 'utf8')


def c_string(c_data):
    return c_data


class CEVENTTIME(ctypes.Structure):
    _fields_ = [
        ('client_creation', ctypes.c_uint64),
        ('server_upload', ctypes.c_uint64)
    ]

    __slots__ = (
        'client_creation', 'server_upload',
        'dtime', 'is_accurate'
    )

    delta_past = datetime.timedelta(days=6 * 30)
    delta_future = datetime.timedelta(days=1)

    def _setup_time(self):
        client_dtime = SerializableDatetime.utcfromtimestamp(
            self.client_creation / 1000.  # timestamp is in millisecs
        )
        server_dtime = SerializableDatetime.utcfromtimestamp(
            self.server_upload / 1000.  # timestamp is in millisecs
        )

        self.dtime = server_dtime
        self.is_accurate = False
        if client_dtime >= server_dtime - CEVENTTIME.delta_past and\
                client_dtime <= server_dtime + CEVENTTIME.delta_future:
            self.dtime = client_dtime
            self.is_accurate = True

    def get_approx_time(self):
        if not self.dtime:
            self._setup_time()
        return self.dtime, self.is_accurate

    def __dumpdict__(self):
        return {
            'dtime': self.dtime,
            'is_accurate': self.is_accurate
        }


class IDInfo(ctypes.Structure):
    _fields_ = [
        ('os', ctypes.c_byte),
    ]

    _os_valid_range = range(3)

    __slots__ = ('os', 'uid')

    def __dumpdict__(self):
        return {
            'os': self.os
        }

    def is_on_android(self):
        return self.os == 1

    def is_on_ios(self):
        return self.os == 2

    def is_on_unknown_os(self):
        return self.os == 0

    def validate(self):
        if self.os not in IDInfo._os_valid_range:
            raise ValueError('Incorrect os value: %s' % self.os)


class GeoIDInfo(IDInfo):
    _fields_ = [
        ('lat', ctypes.c_float),
        ('lon', ctypes.c_float)
    ]

    __slots__ = ('lat', 'lon')

    def has_geo(self):
        # TODO: if client will send actual (0, 0) we will
        # intepretate them as a geo info absence.
        # For now it is acceptable though.
        return (
            round(self.lat, 2) != 0.0 or
            round(self.lon, 2) != 0.0
        )

    def get_location(self):
        if self.has_geo():
            return (self.lat, self.lon)
        return None

    def __dumpdict__(self):
        dct = super(GeoIDInfo, self).__dumpdict__()
        if self.has_geo():
            dct['lat'] = round(self.lat, 6)
            dct['lon'] = round(self.lon, 6)
        return dct


class CUSERINFO(GeoIDInfo):
    _fields_ = [
        ('raw_uid', (ctypes.c_char * 32)),
    ]

    __slots__ = ('raw_uid',)

    def setup(self):
        self.validate()
        setattr(self, 'uid', int(self.raw_uid, 16))

    def stripped(self):
        if self.has_geo():
            return GeoIDInfo(
                uid=self.uid, os=self.os,
                lat=self.lat, lon=self.lon
            )
        return IDInfo(
            uid=self.uid, os=self.os
        )


CCALLBACK = ctypes.CFUNCTYPE(
    None,
    ctypes.c_char_p,
    ctypes.POINTER(CEVENTTIME),
    ctypes.POINTER(CUSERINFO),
    ctypes.POINTER(ctypes.c_char_p),
    ctypes.c_int
)  # key, event_time, user_info, data, data_len


def iterate_events(stream_processor, events_limit):
    base_path = os.path.dirname(os.path.abspath(__file__))
    c_module = ctypes.cdll.LoadLibrary(
        os.path.join(base_path, 'iterate_events.so')
    )
    use_keys = tuple(itertools.chain.from_iterable(
        e.keys for e in stream_processor.__events__
    ))
    keylist_type = ctypes.c_char_p * len(use_keys)
    c_module.Iterate.argtypes = [CCALLBACK, keylist_type, ctypes.c_int]
    c_module.Iterate(
        CCALLBACK(
            stream_processor.process_event
        ),
        keylist_type(*use_keys),
        len(use_keys),
        events_limit
    )
