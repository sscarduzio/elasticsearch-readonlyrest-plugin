#!/usr/bin/python
import crypt
import random
import sys
import string



def sha512_crypt(password, salt=None, rounds=None):
    if salt is None:
        rand = random.SystemRandom()
        salt = ''.join([rand.choice(string.ascii_letters + string.digits)
                        for _ in range(8)])

    prefix = '$6$'
    if rounds is not None:
        rounds = max(1000, min(999999999, rounds or 5000))
        prefix += 'rounds={0}$'.format(rounds)
    return crypt.crypt(password, prefix + salt)


if __name__ == '__main__':
    if len(sys.argv) > 1:
        print sha512_crypt(sys.argv[1], rounds=65635)
    else:
        print "Argument is missing, <password>"
